(ns petrglad.pendel-test
  (:require [clojure.test :refer :all]
            [petrglad.pendel :as pendel]
            [petrglad.common.maps :as maps])
  (:import (org.slf4j LoggerFactory)
           (clojure.lang ExceptionInfo)))

(def log (LoggerFactory/getLogger (name (ns-name *ns*))))

(defn stop-runner [this]
  (let [updated (update this :run? reset! false)]
    (.join (:thread this))
    updated))

(def contrived-system
  {:component-a {:this 12}
   :component-b {:requires #{:component-a}
                 :start    (fn [_this {a :component-a}]
                             (assert (= a 12))
                             (fn [x] (+ a (or x 0))))}
   :consumer    {:requires #{:component-a :component-b}
                 :get      :sink
                 :start    (fn [_this {a :component-a b :component-b}]
                             (assert (= a 12) (= (b 33) 45))
                             (let [sink (atom [])
                                   received (atom #{})
                                   run? (atom true)
                                   t (Thread.
                                       ^Runnable
                                       (fn []
                                         (loop []
                                           (Thread/sleep (rand-int 15))
                                           (loop [k (peek @sink)]
                                             (when k
                                               (.trace log "Got {}" k)
                                               (swap! received conj k)
                                               (swap! sink pop)
                                               (recur (peek @sink))))
                                           (when @run?
                                             (recur)))))]
                               (.start t)
                               {:run? run? :thread t :sink sink :received received}))
                 :stop     stop-runner}
   :unused      {:requires #{:consumer}}
   :producer    {:requires #{:consumer}
                 :this     {:run? (atom true)}
                 :start    (fn [_this {consumer :consumer}]
                             (assert (vector? @consumer))
                             (.debug log "Consumer {} " consumer)
                             (let [run? (atom true)
                                   sent (atom #{})
                                   t (Thread.
                                       ^Runnable
                                       (fn []
                                         (loop [k 0]
                                           (Thread/sleep (rand-int 15))
                                           (.trace log "Put {}" k)
                                           (swap! consumer conj k)
                                           (swap! sent conj k)
                                           (when @run?
                                             (recur (inc k))))))]
                               (.start t)
                               {:run? run? :thread t :sent sent}))
                 :stop     stop-runner}})

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
          (pendel/start {:a {:requres #{}}} #{:a}))) ;; Misprint
    (is (thrown-with-msg? ExceptionInfo #"(?i).*unknown.+"
          (pendel/start {:a {:requires #{:a}}} #{:z}))) ;; In start requires
    (is (thrown-with-msg? ExceptionInfo #"(?i).*unknown.+"
          (pendel/start {:a {:requires #{:z}}} #{:a}))))) ;; In component requires

(deftest test-cycles
  (testing "Cycle detection."
    (is (thrown-with-msg? ExceptionInfo #"(?i).+cycle.+"
          (pendel/start {:a {:requires #{:b}} :b {:requires #{:a}}} #{:a})))
    (is (thrown-with-msg? ExceptionInfo #"(?i).+cycle.+"
          (pendel/start {:a {:requires #{:a}}} #{:a})))
    (is (pendel/start {:a {:requires #{:a}} :b {}} #{:b})))) ;; Unused cycle

(deftest test-start-stop
  (testing "Example start/stop."
    (loop [cnt 3
           system contrived-system]
      (let [started (pendel/start system #{:producer})]
        (is (= (-> (maps/key-set contrived-system) (disj :unused))
              (keys-by-status started :started)))
        (is (= #{:unused} (keys-by-status started :stopped)))
        (Thread/sleep 100)
        (let [stopped (pendel/stop started)]
          (is (= (maps/key-set contrived-system)
                (keys-by-status stopped :stopped)))
          (let [received @(get-in stopped [:consumer :this :received])
                sent @(get-in stopped [:producer :this :sent])]
            ;;(is (seq received))
            (is (= received sent)))
          (when (< 0 cnt)
            (recur (dec cnt) stopped)))))))

(deftest test-failures
  (testing "Component failures"
    (let [s {:a {:start (fn [_this _deps]
                          (throw (Exception. "Cannot start this.")))}}]
      (try
        (pendel/start s #{:a})
        (assert false)
        (catch ExceptionInfo ex
          (let [{system :system} (ex-data ex)]
            (is (= (pendel/normalize-system s) system)) ;;; Not changed
            (is (= system (pendel/stop system)))))))
    (let [s {:a {}
             :b {:requires #{:a}
                 :start    (fn [_this _deps]
                             (throw (Exception. "Cannot start this.")))}}]
      (try
        (pendel/start s #{:b})
        (assert false)
        (catch ExceptionInfo ex
          (let [{system :system} (ex-data ex)]
            (is (= {:started #{:a} :stopped #{:b}}
                  (component-statuses system)))
            (let [stopped (pendel/stop system)]
              (is (= {:stopped #{:a :b}}
                    (component-statuses stopped))))))))))

(deftest test-partial-stop
  (testing "Partial start/stop"
    (let [s {:a {}
             :b {:requires #{:a}}
             :c {:requires #{:b}}}
          s1 (pendel/start s #{:c})
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s2 (pendel/stop s1)
          _ (is (= {:stopped #{:a :b :c}}
                  (component-statuses s2)))
          s3 (pendel/start s2)
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s4 (pendel/stop s3 #{:b})
          _ (is (= {:started #{:a} :stopped #{:b :c}}
                  (component-statuses s4)))
          s5 (pendel/start s4 #{:c})
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s5)))]))
  (testing "Partial start/stop disjoined"
    (let [s {:a {}
             :b {}
             :c {}}
          s1 (pendel/start s)
          _ (is (= {:started #{:a :b :c}}
                  (component-statuses s1)))
          s2 (pendel/stop s1 #{:a})
          _ (is (= {:stopped #{:a} :started #{:b :c}}
                  (component-statuses s2)))
          s3 (pendel/stop s2 #{:b})
          _ (is (= {:stopped #{:a :b} :started #{:c}}
                  (component-statuses s3)))
          s4 (pendel/start s3 #{:a})
          _ (is (= {:stopped #{:b} :started #{:a :c}}
                  (component-statuses s4)))
          s5 (pendel/stop s4 #{:a :b})
          _ (is (= {:stopped #{:a :b} :started #{:c}}
                  (component-statuses s5)))])))
