(ns petrglad.pendel-test
  (:require [clojure.test :refer :all]
            [petrglad.pendel :as pendel]
            [petrglad.common :as common])
  (:import (clojure.lang ExceptionInfo)
           (org.slf4j LoggerFactory Logger)))

(def log (LoggerFactory/getLogger (name (ns-name *ns*))))

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
                                                     (Thread/sleep (rand-int 15))
                                                     (.debug log "Got {}" (peek @sink))
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
                                       (.debug log "Consumer {} " consumer)
                                       (let [run? (atom true)
                                             t (Thread.
                                                 ^Runnable
                                                 (fn []
                                                   (loop []
                                                     (Thread/sleep (rand-int 15))
                                                     (let [v (rand-int 100)]
                                                       (.debug log "Put {}" v)
                                                       (swap! consumer conj v))
                                                     (when @run?
                                                       (recur)))))]
                                         (.start t)
                                         {:run? run? :thread t}))
                           :stop     (fn [{run? :run?}]
                                       (reset! run? false))}}]
      (let [s (pendel/start s #{:producer})]
        (.debug log "Started system {}" (common/map-vals
                                          #(select-keys % #{:requires :status :this :value})
                                          s))
        (Thread/sleep 100)
        (pendel/stop s)))))
