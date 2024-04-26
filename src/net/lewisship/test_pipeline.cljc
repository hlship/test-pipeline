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

(ns net.lewisship.test-pipeline
  "Per-test pipeline of composable steps, including simple mocks and capturing of logging events.

  A test is implemented as series of step functions; each step receives a context map, does work, and passes
  the (sometimes modified) context map to the next step.

  The goal is to make tests flatter (fewer nested scopes), more
  readable, and to make various kinds of steps more composable."
  (:require [com.walmartlabs.test-reporting :as test-reporting]
            clojure.test
            matcher-combinators.test
            [mockfn.macros :as mfn]
            [net.lewisship.test-pipeline.internal :refer [get-and-clear!]]
            #?(:clj [clojure.tools.logging.test :refer [with-log]]))
  #?(:cljs (:require-macros net.lewisship.test-pipeline)))

(defn ^:private should-halt?
  [context]
  (some #(% context) (::halt-checks context)))

(defn halt
  "Called instead of [[continue]] to immediately halt the execution of the pipeline without
  executing any further steps. This is used when an error has been detected that will invalidate
  behavior checks in later steps.

  When execution is halted, the check that ensures all steps executed is disabled."
  [context]
  (reset! (::*halted context) true))

(defn add-halt-check
  "Adds a halt check function to the context, for use by [[continue]].

  Returns the updated context, which should then be passed to `continue`."
  [context check-fn]
  (assert (map? context))
  (assert (ifn? check-fn))
  (update context ::halt-checks conj check-fn))

(defn continue
  "Called from a step function to pass the context to the next step function in the pipeline.
  Does nothing if there are no more steps to execute.

  continue is responsible for halt checks; each added halt check function is passed the context, and
  if any of them return a truthy value, the pipeline is halted, as with [[halt]]."
  [context]
  (assert (map? context))
  (if (should-halt? context)
    (halt context)
    (let [[next-step & more-steps] (-> context ::steps)]
      (when next-step
        (next-step (assoc context ::steps more-steps))))))

(defn halt-on-failure
  "Adds a halt check that terminates the pipeline if any test errors or
  test failures are subsequently reported."
  [context]
  (let [counters (test-reporting/snapshot-counters)
        check-fn (fn [_]
                   (not= counters (test-reporting/snapshot-counters)))]
    (continue (add-halt-check context check-fn))))

(defn execute
  "The main entrypoint: executes a sequence of step functions as a pipeline.

   Each step in `steps` may be a step function, or nil, or a seq of steps (recursively).
   The provided seq is flattened and nils are removed before constructing the final pipeline.

   A step function is responsible for one portion of setting up the test environment, and may perform part of the
   test execution as well. Generally, the final step function is the most specific to a particular test,
   and will be where most assertions occur.

   A step function is passed a context; the step function's job is to modify the context before
   passing the context to `continue`; this call is often wrapped
   in a `try` (to clean up resources) and/or `with-redefs` (to override functions for testing).

   Each step function must call [[continue]] (except the final step function,
   for which the call to `continue` is optional and does nothing); however this check is skipped if
   the execution pipeline is halted."
  [& steps]
  (assert (seq steps))
  (let [*executed (atom false)
        *halted   (atom false)
        step-fns  (->> steps flatten (remove nil?))
        ;; Want to add a hook before calling the final step fn to ensure it actually gets
        ;; invoked (that all prior steps called continue).
        tail-fn   (last step-fns)
        check-fn  (fn [context]
                    (reset! *executed true)
                    (continue context))
        step-fns' (concat (butlast step-fns) [check-fn tail-fn])]
    (continue {::steps       step-fns'
               ::*halted     *halted
               ::halt-checks []})
    (when-not @*halted
      (assert @*executed
              "no exception was thrown, but not all steps executed"))
    nil))

;; Common steps

(defn update-in-context
  "Returns a step function that performs an `update-in` on the context during execution."
  [ks f & args]
  (fn [context]
    (continue (apply update-in context ks f args))))

(defn assoc-in-context
  "Returns a step function that performs an `assoc-in` on the context during execution."
  [ks v]
  (fn [context]
    (continue (assoc-in context ks v))))

(defmacro mock
  "Expands to a step function that mocks the var with the corresponding value
  (as with `clojure.core/with-redefs`)."
  [mock-var mock-fn]
  `(fn [context#]
     (with-redefs [~mock-var ~mock-fn]
       (continue context#))))

(defmacro spy
  "Expands to a step function that mocks a function with a spy.

  The spy records into an atom each list of arguments it is passed, before passing the arguments to the spied function.

  Optionally, a mock function (as with [[mock]]) can be supplied to replace the spied function.

  Usage:
  ```
    (spy db/put-row (fn [_ row-data] ...))
  ```

  The [[calls]] macro retrieves the calls made to the spy."
  ([spy-var]
   `(spy ~spy-var ~spy-var))
  ([spy-var mock-fn]
   `(fn [context#]
      (let [*atom#   (atom [])
            mock-fn# ~mock-fn]
        (with-redefs [~spy-var (fn [& args#]
                                 (swap! *atom# conj args#)
                                 (apply mock-fn# args#))]
          (continue (assoc-in context# [::spys #'~spy-var] *atom#)))))))

(defmacro calls
  "Returns calls to-date of the given spy, clearing the list of calls as a side effect.

  Returns a vector of lists of arguments."
  [context spy-var]
  (assert (symbol? spy-var))
  `(let [spys#  (get ~context ::spys)
         *atom# (or (get spys# #'~spy-var)
                    (throw (ex-info (str "no spy for " #'~spy-var)
                                    {:spies (->> spys# keys (sort-by str))})))]
     (get-and-clear! *atom#)))

(defmacro reporting
  "Expands to a step function based on `com.walmartlabs.test-reporting/reporting` that extracts a value from the context and reports its value
  if a test fails."
  [k]
  (assert (keyword? k))
  `(fn [context#]
     (test-reporting/reporting {'~(-> k name symbol) (get context# ~k)}
                               (continue context#))))

(defmacro bind
  "Evaluates to a step function that binds the variable to a value before continuing."
  {:added "0.2"}
  [bind-var bound-value]
  `(fn [context#]
     (binding [~bind-var ~bound-value]
       (continue context#))))

(defn split
  "Splits the execution pipeline.  Returns a step function that will sequence through
  the provided seq of step fns and pass the context to each in turn.

  Thus, any steps further down the pipeline will be invoked multiple times."
  [step-fns]
  (assert (seq step-fns))
  (assert (every? fn? step-fns))
  (fn [context]
    (doseq [f step-fns]
      (f context))))

;; capture-logging only makes sense for Clojure as clojure.tools.logging is not implemented for ClojureScript.

#?(:clj
   (defn capture-logging
     "A step function that captures logging events from `clojure.tools.logging`.
     Use the `clojure.tools.logging.test` namespace to query what has been logged."
     [context]
     (with-log
       (continue context))))


(defmacro then
  "Evaluates the provided expressions during execution, before continuing to the next test step function."
  {:added "0.5"}
  [& exprs]
  `(fn [context#]
     ~@exprs
     (continue context#)))

(defmacro is
  "Wrapper around clojure.test/is."
  {:added "0.5"}
  ([expr]
   `(then (clojure.test/is ~expr)))
  ([expr message]
   `(then (clojure.test/is ~expr ~message))))

(defmacro is-context
  "Variant of [[is]] where a local binding for the context is provided.

  Example:

      (is-context context (valid? (:result context)) \"expected valid result\")

  The expression and optional message are passed to `clojure.test/is`.

  The context is then passed, unchanged, to [[continue]]."
  {:added "0.7"}
  ([context-symbol expr]
   `(is-context ~context-symbol ~expr nil))
  ([context-symbol expr message]
   {:pre [(simple-symbol? context-symbol)]}
   `(fn [~context-symbol]
      (clojure.test/is ~expr ~message)
      (continue ~context-symbol))))

(defmacro testing
  "Wrapper around clojure.test/testing."
  {:added "0.5"}
  [expr]
  `(fn [context#]
     (clojure.test/testing ~expr
       (continue context#))))

(defmacro cleanup
  "Creates a try/finally to execute the provided cleanup expressions after the pipeline has finished executing."
  {:added "0.6"}
  [& exprs]
  `(fn [context#]
     (try
       (continue context#)
       (finally
         (do ~@exprs)))))

(defmacro providing
  "Wrapper around mockfn.macros/providing.  The argument is the bindings vector."
  {:added "0.6"}
  [bindings]
  {:pre [(vector? bindings)
         (= 0 (mod (count bindings) 2))]}
  `(fn [context#]
     (mfn/providing ~bindings
       (continue context#))))

(defmacro verifying
  "Wrapper around mockfn.macros/verifying.  The argument is the bindings vector."
  {:added "0.6"}
  [bindings]
  {:pre [(vector? bindings)
         (= 0 (mod (count bindings) 3))]}
  `(fn [context#]
     (mfn/verifying ~bindings
       (continue context#))))

(defmacro matches?
  "A wrapper around `(is-context ... (match? ...))` where `match?` is supplied by
  nubank/matcher-combinators.

  Example:
    (p/matches? context {:truth :beauty} (:statement context))

  The context is bound to the provided symbol, which is available to use with
  the pattern (`{:truth :beauty}`) and the expression (`(:statement context)`).
  The context is then passed, unchanged, to [[continue]]."
  {:added "0.7"}
  ([context-symbol pattern expression]
   `(matches? ~context-symbol ~pattern ~expression nil))
  ([context-symbol pattern expression message]
   `(is-context ~context-symbol
                (~'match? ~pattern ~expression)
                ~message)))


