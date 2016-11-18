(ns net.readmarks.compost-test
  (:require [clojure.test :refer :all]
            [net.readmarks.compost :as compost]
            [net.readmarks.common.maps :as maps]
            [clojure.core.async :as async :refer [>!! <!! chan alt!! timeout]]
            [clojure.tools.logging :as logging])
  (:import (clojure.lang ExceptionInfo)))

(def contrived-system
  {:component-a {:this 12} ;;; Taking tech to the limits...
   :component-b {:requires #{:component-a}
                 :start    (fn [_this {a :component-a}]
                             (assert (= a 12))
                             (fn [x] (+ a (or x 0))))}
   :consumer    {:requires #{:component-a :component-b}
                 :get      :sink
                 :start    (fn [_this {a :component-a b :component-b}]
                             (assert (= a 12) (= (b 33) 45))
                             (let [stop (chan)
                                   sink (chan 4)
                                   received (async/thread
                                              (loop [received #{}]
                                                (let [state (alt!!
                                                              [sink] ([v _] v)
                                                              [stop] :stop)]
                                                  (logging/trace "Got" state)
                                                  (if (= :stop state)
                                                    received
                                                    (recur (conj received state))))))]
                               {:stop stop :sink sink :received received}))
                 :stop     (fn stop-runner [this]
                             (>!! (:stop this) true)
                             this)}
   :unused      {:requires #{:consumer}}
   :producer    {:requires #{:consumer}
                 :start    (fn [_this {consumer :consumer}]
                             (assert consumer)
                             (logging/debug "Consumer" consumer)
                             (let [stop (chan)
                                   sent (async/thread
                                          (loop [k 0
                                                 sent #{}]
                                            (<!! (timeout (rand-int 30)))
                                            (let [state (alt!!
                                                          [[consumer k]] :put
                                                          [(timeout 2000)] :timeout
                                                          [stop] :stop)]
                                              (logging/tracef "State %s, k %s" state k)
                                              (case state
                                                :stop sent
                                                :put (recur
                                                       (inc k)
                                                       (conj sent k))))))]
                               {:stop stop :sent sent}))
                 :stop     (fn stop-runner [this]
                             (>!! (:stop this) true)
                             this)}})

(defn component-statuses [system]
  (reduce-kv (fn [m id co]
               (maps/add-assoc m (:status co) id))
    {} system))

(defn keys-by-status [system status]
  (reduce-kv (fn [r k co]
               (if (= status (:status co))
                 (conj r k)
                 r))
    #{} system))

(deftest test-validation
  (testing "System validation."
    (is (thrown-with-msg? ExceptionInfo #"(?i).*unknown.+"
          (compost/start {:a {:requres #{}}} #{:a}))) ;; Misprint
    (is (thrown-with-msg? ExceptionInfo #"(?i).*unknown.+"
          (compost/start {:a {:requires #{:a}}} #{:z}))) ;; In start requires
    (is (thrown-with-msg? ExceptionInfo #"(?i).*unknown.+"
          (compost/start {:a {:requires #{:z}}} #{:a}))))) ;; In component requires

(deftest test-cycles
  (testing "Cycle detection."
    (is (thrown-with-msg? ExceptionInfo #"(?i).+cycle.+"
          (compost/start {:a {:requires #{:b}} :b {:requires #{:a}}} #{:a})))
    (is (thrown-with-msg? ExceptionInfo #"(?i).+cycle.+"
          (compost/start {:a {:requires #{:a}}} #{:a})))
    (is (compost/start {:a {:requires #{:a}} :b {}} #{:b})))) ;; Unused cycle

(deftest test-start-stop
  (testing "Example start/stop."
    (loop [cnt 3
           system contrived-system]
      (let [started (compost/start system #{:producer})]
        (is (= (-> (maps/key-set contrived-system) (disj :unused))
              (keys-by-status started :started)))
        (is (= #{:unused} (keys-by-status started :stopped)))
        (Thread/sleep 100)
        (let [stopped (compost/stop started)]
          (is (= (maps/key-set contrived-system)
                (keys-by-status stopped :stopped)))
          (let [received (<!! (get-in stopped [:consumer :this :received]))
                sent (<!! (get-in stopped [:producer :this :sent]))]
            (is (set? received))
            (is (= sent received)))
          (when (< 0 cnt)
            (recur (dec cnt) stopped)))))))

(deftest test-failures
  (testing "Component failures"
    (let [s {:a {:start (fn [_this _deps]
                          (throw (Exception. "Cannot start this.")))}}]
      (try
        (compost/start s #{:a})
        (assert false)
        (catch ExceptionInfo ex
          (let [{system :system ex-type :type} (ex-data ex)]
            (is (= :net.readmarks.compost/error ex-type))
            (is (= (compost/normalize-system s) system)) ;;; Not changed
            (is (= system (compost/stop system)))))))
    (let [s {:a {}
             :b {:requires #{:a}
                 :start    (fn [_this _deps]
                             (throw (Exception. "Cannot start this.")))}}]
      (try
        (compost/start s #{:b})
        (assert false)
        (catch ExceptionInfo ex
          (let [{system :system ex-type :type} (ex-data ex)]
            (is (= :net.readmarks.compost/error ex-type))
            (is (= {:started #{:a} :stopped #{:b}}
                  (component-statuses system)))
            (let [stopped (compost/stop system)]
              (is (= {:stopped #{:a :b}}
                    (component-statuses stopped))))))))))

(deftest test-partial-stop
  (testing "Partial start/stop"
    (let [s {:a {}
             :b {:requires #{:a}}
             :c {:requires #{:b}}}
          s1 (compost/start s #{:c})
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s2 (compost/stop s1)
          _ (is (= {:stopped #{:a :b :c}}
                  (component-statuses s2)))
          s3 (compost/start s2)
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s4 (compost/stop s3 #{:b})
          _ (is (= {:started #{:a} :stopped #{:b :c}}
                  (component-statuses s4)))
          s5 (compost/start s4 #{:c})
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s5)))]
      s5)) ;; For eastwood
  (testing "Partial start/stop disjoined"
    (let [s {:a {}
             :b {}
             :c {}}
          s1 (compost/start s)
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s2 (compost/stop s1 #{:a})
          _ (is (= {:stopped #{:a} :started #{:b :c}}
                  (component-statuses s2)))
          s3 (compost/stop s2 #{:b})
          _ (is (= {:stopped #{:a :b} :started #{:c}}
                  (component-statuses s3)))
          s4 (compost/start s3 #{:a})
          _ (is (= {:stopped #{:b} :started #{:a :c}}
                  (component-statuses s4)))
          s5 (compost/stop s4 #{:a :b})
          _ (is (= {:stopped #{:a :b} :started #{:c}}
                  (component-statuses s5)))]
      s5))) ;; For eastwood
