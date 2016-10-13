(ns petrglad.pendel-test
  (:require [clojure.test :refer :all]
            [petrglad.pendel :as pendel])
  (:import (clojure.lang ExceptionInfo)))

(deftest test-start
  (testing "Example start/stop."
    (let [s {:component-a {:this 12}
             :component-b {:requires #{:component-a}
                           :start    (fn [_this {a :component-a}]
                                       (assert (= a 12))
                                       (fn [x] (+ a x)))}
             :consumer    {:requires #{:component-a :component-b}
                           :get      :sink
                           :start    (fn [_this {a :component-a b :component-b}]
                                       (assert (= a 12) (= (b 33) 45))
                                       (let [sink (atom [])
                                             run? (atom true)
                                             t (Thread.
                                                 ^Runnable
                                                 (fn []
                                                   (loop []
                                                     (Thread/sleep 1000)
                                                     (println "Got" @sink)
                                                     (swap! sink #(if (seq %) (pop %) %))
                                                     (when @run?
                                                       (recur)))))]
                                         (.start t)
                                         {:run? run? :thread t :sink sink}))
                           :stop     (fn [{run? :run?}]
                                       (reset! run? false))}
             :producer    {:requires #{:consumer}
                           :this     {:run? (atom true)}
                           :start    (fn [_this {consumer :consumer}]
                                       (assert (vector? @consumer))
                                       (let [run? (atom true)
                                             t (Thread.
                                                 ^Runnable
                                                 (fn []
                                                   (loop []
                                                     (Thread/sleep 1000)
                                                     (println "PUT(" consumer ") " (swap! consumer conj (rand-int 100)))
                                                     (when @run?
                                                       (recur)))))]
                                         (.start t)
                                         {:run? run? :thread t}))
                           :stop     (fn [{run? :run?}]
                                       (reset! run? false))}}]
      (try
        (let [s (pendel/start s #{:consumer :producer})]
          (println "Started system" (pendel/map-vals
                                      #(select-keys % #{:requires :status :this :value})
                                      s))
          (Thread/sleep 5000)
          (pendel/stop s)
          (is (= 0 1)))
        (catch ExceptionInfo ex
          (println "ERROR" ex (ex-data ex)))))))
