/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.operators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.util.functions.Func1;
import rx.util.functions.Func2;
import rx.util.functions.Func3;
import rx.util.functions.Func4;
import rx.util.functions.FuncN;
import rx.util.functions.Functions;

public class OperationCombineLatest {

    /**
     * Combines the two given observables, emitting an event containing an
     * aggregation of the latest values of each of the source observables each
     * time an event is received from one of the source observables, where the
     * aggregation is defined by the given function.
     *
     * @param w0                    The first source observable.
     * @param w1                    The second source observable.
     * @param combineLatestFunction The aggregation function used to combine the
     *                              source observable values.
     *
     * @return A function from an observer to a subscription. This can be used
     *         to create an observable from.
     */
    public static <T0, T1, R> Func1<Observer<R>, Subscription> combineLatest(Observable<T0> w0, Observable<T1> w1, Func2<T0, T1, R> combineLatestFunction) {
        Aggregator<R> a = new Aggregator<>(Functions.fromFunc(combineLatestFunction));
        a.addObserver(new CombineObserver<R, T0>(a, w0));
        a.addObserver(new CombineObserver<R, T1>(a, w1));
        return a;
    }

    /**
     * @see #combineLatest(Observable w0, Observable w1, Func2
     * combineLatestFunction)
     */
    public static <T0, T1, T2, R> Func1<Observer<R>, Subscription> combineLatest(Observable<T0> w0, Observable<T1> w1, Observable<T2> w2, Func3<T0, T1, T2, R> combineLatestFunction) {
        Aggregator<R> a = new Aggregator<>(Functions.fromFunc(combineLatestFunction));
        a.addObserver(new CombineObserver<R, T0>(a, w0));
        a.addObserver(new CombineObserver<R, T1>(a, w1));
        a.addObserver(new CombineObserver<R, T2>(a, w2));
        return a;
    }

    /**
     * @see #combineLatest(Observable w0, Observable w1, Func2
     * combineLatestFunction)
     */
    public static <T0, T1, T2, T3, R> Func1<Observer<R>, Subscription> combineLatest(Observable<T0> w0, Observable<T1> w1, Observable<T2> w2, Observable<T3> w3, Func4<T0, T1, T2, T3, R> combineLatestFunction) {
        Aggregator<R> a = new Aggregator<>(Functions.fromFunc(combineLatestFunction));
        a.addObserver(new CombineObserver<R, T0>(a, w0));
        a.addObserver(new CombineObserver<R, T1>(a, w1));
        a.addObserver(new CombineObserver<R, T2>(a, w2));
        a.addObserver(new CombineObserver<R, T3>(a, w3));
        return a;
    }

    private static class CombineObserver<R, T> implements Observer<T> {

        final Observable<T> w;
        final Aggregator<R> a;
        private Subscription subscription;

        public CombineObserver(Aggregator<R> a, Observable<T> w) {
            this.a = a;
            this.w = w;
        }

        public synchronized void startWatching() {
            if (subscription != null)
                throw new RuntimeException("This should only be called once.");
            subscription = w.subscribe(this);
        }

        @Override
        public void onCompleted() {
            a.complete(this);
        }

        @Override
        public void onError(Exception e) {
            a.error(e);
        }

        @Override
        public void onNext(T args) {
            a.next(this, args);
        }
    }

    /**
     * Receive notifications from each of the observables we are reducing and
     * execute the combineLatestFunction whenever we have received an event from
     * one of the observables, as soon as each Observable has received at least
     * one event.
     */
    private static class Aggregator<R> implements Func1<Observer<R>, Subscription> {

        private Observer<R> observer;

        private final FuncN<R> combineLatestFunction;
        private final AtomicBoolean running = new AtomicBoolean(true);

        // used as an internal lock for handling the latest values and the completed state of each observer
        private final Object lockObject = new Object();

        /**
         * Store when an observer completes.
         * <p>
         * Note that access to this set MUST BE SYNCHRONIZED via 'lockObject'
         * above.
         *
         */
        private final Set<CombineObserver<R, ?>> completed = new HashSet<>();

        /**
         * The latest value from each observer
         * <p>
         * Note that access to this set MUST BE SYNCHRONIZED via 'lockObject'
         * above.
         *
         */
        private final Map<CombineObserver<R, ?>, Object> latestValue = new HashMap<>();

        /**
         * Whether each observer has a latest value at all.
         * <p>
         * Note that access to this set MUST BE SYNCHRONIZED via 'lockObject'
         * above.
         *
         */
        private final Set<CombineObserver<R, ?>> hasLatestValue = new HashSet<>();

        /**
         * Ordered list of observers to combine. No synchronization is necessary
         * as these can not be added or changed asynchronously.
         */
        private final List<CombineObserver<R, ?>> observers = new LinkedList<>();

        public Aggregator(FuncN<R> combineLatestFunction) {
            this.combineLatestFunction = combineLatestFunction;
        }

        /**
         * Receive notification of a Observer starting (meaning we should
         * require it for aggregation)
         *
         * @param w The observer to add.
         */
        <T> void addObserver(CombineObserver<R, T> w) {
            observers.add(w);
        }

        /**
         * Receive notification of a Observer completing its iterations.
         *
         * @param w The observer that has completed.
         */
        <T> void complete(CombineObserver<R, T> w) {
            synchronized (lockObject) {
                // store that this CombineLatestObserver is completed
                completed.add(w);
                // if all CombineObservers are completed, we mark the whole thing as completed
                if (completed.size() == observers.size())
                    if (running.get()) {
                        // mark ourselves as done
                        observer.onCompleted();
                        // just to ensure we stop processing in case we receive more onNext/complete/error calls after this
                        running.set(false);
                    }
            }
        }

        /**
         * Receive error for a Observer. Throw the error up the chain and stop
         * processing.
         */
        void error(Exception e) {
            observer.onError(e);
            /*
             * tell all observers to unsubscribe since we had an error
             */
            stop();
        }

        /**
         * Receive the next value from an observer.
         * <p>
         * If we have received values from all observers, trigger the
         * combineLatest function, otherwise store the value and keep waiting.
         *
         * @param w
         * @param arg
         */
        <T> void next(CombineObserver<R, T> w, T arg) {
            if (observer == null)
                throw new RuntimeException("This shouldn't be running if an Observer isn't registered");

            /*
             * if we've been 'unsubscribed' don't process anything further even
             * if the things we're watching keep sending (likely because they
             * are not responding to the unsubscribe call)
             */
            if (!running.get())
                return;

            // define here so the variable is out of the synchronized scope
            Object[] argsToCombineLatest = new Object[observers.size()];

            // we synchronize everything that touches latest values
            synchronized (lockObject) {
                // remember this as the latest value for this observer
                latestValue.put(w, arg);

                // remember that this observer now has a latest value set
                hasLatestValue.add(w);

                // if all observers in the 'observers' list have a value, invoke the combineLatestFunction
                for (CombineObserver<R, ?> rw : observers)
                    if (!hasLatestValue.contains(rw))
                        // we don't have a value yet for each observer to combine, so we don't have a combined value yet either
                        return;
                // if we get to here this means all the queues have data
                int i = 0;
                for (CombineObserver<R, ?> _w : observers)
                    argsToCombineLatest[i++] = latestValue.get(_w);
            }
            // if we did not return above from the synchronized block we can now invoke the combineLatestFunction with all of the args
            // we do this outside the synchronized block as it is now safe to call this concurrently and don't need to block other threads from calling
            // this 'next' method while another thread finishes calling this combineLatestFunction
            observer.onNext(combineLatestFunction.call(argsToCombineLatest));
        }

        @Override
        public Subscription call(Observer<R> observer) {
            if (this.observer != null)
                throw new IllegalStateException("Only one Observer can subscribe to this Observable.");
            this.observer = observer;

            /*
             * start the observers
             */
            for (CombineObserver<R, ?> rw : observers)
                rw.startWatching();

            return new Subscription() {
                @Override
                public void unsubscribe() {
                    stop();
                }
            };
        }

        private void stop() {
            /*
             * tell ourselves to stop processing onNext events
             */
            running.set(false);
            /*
             * propogate to all observers to unsubscribe
             */
            for (CombineObserver<R, ?> rw : observers)
                if (rw.subscription != null)
                    rw.subscription.unsubscribe();
        }
    }
}
