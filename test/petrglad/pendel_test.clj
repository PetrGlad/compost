(ns petrglad.pendel-test
  (:require [clojure.test :refer :all]
            [petrglad.pendel :as pendel]
            [petrglad.common.maps :as maps])
  (:import (org.slf4j LoggerFactory)
           (clojure.lang ExceptionInfo)))

(def log (LoggerFactory/getLogger (name (ns-name *ns*))))

(def contrived-system
  {:component-a {:this 12}
   :unused      {:requires #{:consumer}}
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
                                           (let [k (peek @sink)]
                                             (.trace log "Got {}" (b k))
                                             (swap! received conj k))
                                           (swap! sink #(if (seq %) (pop %) %))
                                           (when @run?
                                             (recur)))))]
                               (.start t)
                               {:run? run? :thread t :sink sink :received received}))
                 :stop     (fn [this]
                             (update this :run? reset! false))}
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
                 :stop     (fn [this]
                             (update this :run? reset! false))}})

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
          (is (= (get-in stopped [:consumer :received])
                (get-in stopped [:producer :sent])))
          (when (< 0 cnt)
            (recur (dec cnt) stopped)))))))

(deftest test-failures
  (testing "Component failures"
    (try
      (pendel/start
        {:a {:start (fn [_this _deps]
                      (throw (Exception. "Cannot start this.")))}}
        #{:a})
      (assert false)
      (catch ExceptionInfo ex
        ;;; FIXME Add assertions
        (pendel/stop (:system ex))
        #_(.error log (pr-str (ex-data ex)))))))

