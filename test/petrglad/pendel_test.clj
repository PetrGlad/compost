(ns petrglad.pendel-test
  (:require [clojure.test :refer :all]
            [petrglad.pendel :as pendel])
  (:import (clojure.lang ExceptionInfo)))

(deftest test-start
  (testing "Example start/stop."
    (let [s {:component-a {:this 12}
             :component-b {:requires #{:component-a}
                           :start    (fn [_this {a :component-a}]
                                       (fn [x] (+ a x)))}
             :consumer    {:requires #{:component-a :component-b}
                           :this     {:thread nil :sink (atom [])}
                           :get      :sink
                           :start    (fn [_this {a :component-a b :component-b}]
                                       (let [sink (atom [])
                                             t (Thread. ^Runnable
                                                        (fn []
                                                          (loop []
                                                            (Thread/sleep 1000)
                                                            (println "GET(" a ", " b ") " (first sink))
                                                            (swap! sink rest)
                                                            (recur))))]
                                         (.start t)
                                         {:thread t :sink sink}))}
             :producer    {:start (fn [_this {consumer :consumer}]
                                    (let [t (Thread. ^Runnable
                                                     (fn []
                                                       (loop []
                                                         (Thread/sleep 1000)
                                                         (println "PUT(" consumer ") " (swap! consumer conj (rand-int 100)))
                                                         (recur))))]
                                      (.start t)
                                      {:thread t}))}}]
      (try
        (let [s (pendel/start s #{:consumer})]
         (Thread/sleep 10000)
         (pendel/stop s)
         (is (= 0 1)))
        (catch ExceptionInfo ex
          (println "ERROR" ex (ex-data ex)))))))
