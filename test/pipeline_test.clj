; Copyright (c) 2022-present Howard Lewis Ship.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.lewisship.test-pipeline :as p]
            [com.walmartlabs.test-reporting :refer [reporting *reporting-context*]]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :refer [logged?]]
            [com.walmartlabs.test-reporting :as test-reporting])
  (:import (clojure.lang ExceptionInfo)))

(deftest identifies-when-step-fails-to-delegate
  ;; With all this delegation, it's really important to know that execution made it
  ;; at least as far as the provided tail function (otherwise, important assertions might
  ;; be skipped, leading to false test successes).
  (let [e (is (thrown? Throwable
                (p/execute
                  ;; This step function doesn't delegate to the next step function.
                  (constantly nil)
                  identity)))]
    (reporting {:exception e}
      (is (string/includes?
            (or (ex-message e) "")
            "no exception was thrown, but not all steps executed")))))

(deftest steps-execute-before-tail
  (let [*context (atom nil)]
    (p/execute
      #(p/continue (assoc % :from-step true))
      #(reset! *context %))
    (is (= true
          (-> *context deref :from-step)))))

(deftest steps-compose-in-order
  (let [*context (atom nil)
        tag (fn [k] (fn [context]
                      (p/continue (update context :steps conj k))))]
    (p/execute
      (fn [context]
                 (p/continue (assoc context :steps [:step-1])))
      (tag :step-2)
      [(tag :step-3) (tag :step-4)
       [(tag :step-5) nil]]
      (tag :step-6)
      (fn [context]
        (reset! *context context)))
    (is (= [:step-1 :step-2 :step-3 :step-4 :step-5 :step-6]
          (-> *context deref :steps)))))

(defn mocked-fn
  []
  1)

(deftest mocking
  (is (= 1 (mocked-fn)))
  (p/execute
    (p/mock mocked-fn (constantly :mocked))
    (fn [_context]
      (is (= :mocked (mocked-fn))))))

(defn put-row
  "This is a placeholder for some kind of DB access helper."
  [_component table row-data]
  {:row row-data
   :table table})

(defn execute
  "A second fake DB function."
  [_component _query])

(deftest spying
  ;; put-row is just a convenient example function to spy on
  (p/execute
    (p/spy put-row)
    ;; Have a second, just to check the exception thrown
    ;; when an unknown spy is requested.
    (p/spy execute (constantly nil))
    (fn [context]
      (testing "Captures calls to spied var"
        (is (= {:table :foo
                :row 1}
              (put-row nil :foo 1)))
        (is (= {:table :bar
                :row 2}
              (put-row nil :bar 2)))

        (is (= [[nil :foo 1]
                [nil :bar 2]]
              (p/calls context put-row))))

      (testing "p/calls resets arg collection on the spied var"

        (put-row nil :baz 3)

        (is (= [[nil :baz 3]]
              (p/calls context put-row))))

      (testing "p/calls only works with spied vars"
        (when-let [e (is (thrown? ExceptionInfo (p/calls context spying)))]
          (is (= "no spy for #'pipeline-test/spying" (ex-message e)))
          (is (= {:spies [#'execute
                          #'put-row]}
                (ex-data e))))))))

(deftest spy-with-mock
  (is (= {:table :table
          :row :row-data}
        (put-row :component :table :row-data)))
  (p/execute
    (p/spy put-row (constantly :mock-result))
    (fn [context]
      (is (= [] (p/calls context put-row)))
      (is (= :mock-result
            (put-row :component :table :row-data)))
      (is (= [[:component
               :table
               :row-data]]
            (p/calls context put-row))))))

(defn ^:private roll-back-test-failure-counters
  [counters]
  (dosync
    (commute clojure.test/*report-counters*
             #(-> %
                  (dissoc :fail :error)
                  (merge counters)))))

(deftest reporting-macro
  (let [counters (test-reporting/snapshot-counters)]
    (p/execute
    p/halt-on-failure
    (fn [context]
               (p/continue (assoc context :some-data 42
                                          :other-data 99
                                          :last-data :not-included)))
    (p/reporting :some-data)
    (p/reporting :other-data)
    (fn [_]
      (is (= :truth :beauty)
          "forced exception to exercise reporting")
      (is (= '{some-data 42
               other-data 99}
            *reporting-context*))))
    (roll-back-test-failure-counters counters)))

(deftest split
  (let [*contexts (atom [])
        *first (atom 0)
        *last (atom 0)
        set-tag (fn [i]
                  (fn [context]
                    (p/continue (assoc context :tag i))))]
    (p/execute
      (fn [context]
        (p/continue (assoc context :first (swap! *first inc))))
      (p/split [(set-tag :a) (set-tag :b) (set-tag :c)])
      (fn [context]
        (p/continue (assoc context :last (swap! *last inc))))
      (fn [context]
        (swap! *contexts conj (select-keys context [:first :last :tag]))))
    (is (= 1 @*first))
    (is (= 3 @*last))
    (is (= [{:first 1 :last 1 :tag :a}
            {:first 1 :last 2 :tag :b}
            {:first 1 :last 3 :tag :c}]
          @*contexts))))

(deftest update-in-context
  (p/execute
    (p/assoc-in-context [:foo] 1)
    (p/assoc-in-context [:bar :baz] 2)
    (p/update-in-context [:bar :baz] inc)
    #(is (= {:foo 1
             :bar {:baz 3}}
           (select-keys % [:foo :bar])))))

(deftest logging-capture
  (p/execute
    p/capture-logging
    (fn [_]
      (log/error "Inside step")
      (log/error "Second log")
      (is (logged? 'pipeline-test :error "Inside step"))
      (is (logged? 'pipeline-test :error "Second log")))))

(deftest halt-bypasses-last-step-check
  (p/execute
   p/halt
    (fn [_]
      (throw (IllegalStateException. "should not be reachable"))))
  (assert true "no exception thrown"))

(deftest halt-on-failure
  (let [counters (test-reporting/snapshot-counters)]
    (p/execute
      (fn [context]
        (is (= 1 1))
        (p/continue context))
      p/halt-on-failure
      (fn [context]
        ;; Note: Cursive will highlight this line, even though we
        ;; "back it out".
        (is (= 1 2) "induced failure - ignore")
        (p/continue context))
      ;; Because of halt-on-failure, this is never reached
      (fn [_]
        (throw (IllegalStateException. "should have halted"))))
    (roll-back-test-failure-counters counters)))

(def ^:dynamic *bound-var* :default)

(deftest pipeline-binding
  (let [*value (atom nil)]
    (p/execute
      (p/bind *bound-var* :override)
      (fn [_]
        (reset! *value *bound-var*)))
    (is (= :override @*value))))

(defn redef-target
  [x]
  (inc x))

(deftest pipeline-redef
  (is (= 4 (redef-target 3)))
  (let [*value (atom nil)]
    (p/execute
      (p/redef redef-target #(* 2 %))
      (fn [_]
        (reset! *value (redef-target 10))))
    (is (= 20 @*value)))
  (is (= 100 (redef-target 99))))