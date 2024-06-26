# io.github.hlship/test-pipeline

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hlship/test-pipeline.svg)](https://clojars.org/io.github.hlship/test-pipeline)
[![CI](https://github.com/hlship/test-pipeline/actions/workflows/config.yml/badge.svg)](https://github.com/hlship/test-pipeline/actions/workflows/config.yml)
[![cljdoc badge](https://cljdoc.org/badge/io.github.hlship/test-pipeline)](https://cljdoc.org/d/io.github.hlship/test-pipeline)

`test-pipeline` is a small (very small!) library that can be used to improve your Clojure (or ClojureScript) 
test suite.

The central idea is that tests can be implemented as a series of reusable, composable, cooperating _steps_.
 
A context map is passed to each step function, which may add or change keys in the context map before passing it to the 
next step.
This may be familiar: the same pattern shows up in [Ring](https://github.com/ring-clojure/ring) as middleware, where
each function passes a request map to the next function.

## Why would steps need to communicate?

Allowing steps to communicate allows for clearer code, and better code reuse between tests.

This can be explained in the context of an example test from our application's code base; 
like many of our tests, it mocks up our primary backend
service (order services, or OS) with a fixed response from a previously stored file:

```clojure
(deftest cancel-order
  (let [order-path "os-responses/curbside-pickup-and-fc-delivery.json"
        cid (order-path->cid order-path)]
    (with-test-system [system (new-test-system {:os-client (mocks/mock-os-client
                                                              (tc/read-resource-as-os-payload order-path))})]
      (with-redefs [validate-auth (mock-validate-auth cid)]
        (with-now "2020-08-24T20:00:00.000-0800"
          (let [transaction-id (gen/uuid)
                q "
              mutation($input: CancelOrderInput) {
                cancelOrder(input: $input) {
                   result: cancellationResult {
                         status
                         statusText
                         statusSubText
                         omsCode
                      }
                   }
              }"
                variables {:input {:orderId "8508200004824"
                                   :transactionId transaction-id
                                   :subReasonCode "209"
                                   :orderLines [{:lineId "1"
                                                 :quantity 1}]
                                   :cancelAction "CANCEL_NOW"}}
                response (process-request system q variables)]
            (reporting response
                       (is (= 200 (:status response)))
                       (is (= {:data
                               {:cancelOrder
                                {:result
                                 {:status "SUCCESS"
                                  :statusText "Canceled"
                                  :statusSubText "You canceled this item on Aug 24"
                                  :omsCode nil}}}}
                              (:body response))))))))))
```

This test is organized around a GraphQL request and response; we have
to provide mock components, start and stop a
[Component](https://github.com/stuartsierra/component) system,
override the current date/time, send the request into the system, and finally make assertions
about the response.

Our premise is that the test is somewhat difficult to understand and maintain; the code reflects
_how_ to do the work of the test, rather than what behavior the test is designed to verify.

By contrast, a rewrite of the same test to use `test-pipeline` (as alias `p`)
is less busy, less deeply nested, and easier to read and maintain:

```clojure
(deftest cancel-order
  (p/execute
    default-system
    (force-os-response "os-responses/curbside-pickup-and-fc-delivery.json")
    (force-now "2020-08-24T20:00:00.000-0800")
    start-system
    (send-request
      "mutation($input: CancelOrderInput) {
          cancelOrder(input: $input) {
             result: cancellationResult {
                   status
                   statusText
                   statusSubText
                   omsCode
             }
         }
      }"
      {:input {:orderId "8508200004824"
               :transactionId (gen/uuid)
               :subReasonCode "209"
               :orderLines [{:lineId "1"
                             :quantity 1}]
               :cancelAction "CANCEL_NOW"}})
    expect-success
    (expect-data {:cancelOrder
                  {:result
                   {:status "SUCCESS"
                    :statusText "Canceled"
                    :statusSubText "You canceled this item on Aug 24"
                    :omsCode nil}}})))
```

This style of test reads more like a recipe, with more clearly deliniated steps
that all work together to accomplish the final result.

Again, most of these functions are specific to our application.  `default-system`
is a step function that initializes the base Component system map and places it in
the :system key of the context.  `force-os-response` reads the JSON file and
mocks the Component responsible for communicating with the external system; it can
also mock the authentication function.  

Because `start-system` is an explicit step, it is easy to inject mocks into the system map
in whatever order is convenient, prior to the system being started.

`send-request` sends the request and captures
the response as context key :response.  `expect-success` asserts that the response
is status 200 and no GraphQL errors are present.  `expect-data` asserts that the :data key
of the body matches the provided value.

What we've done is establish a _convention_ for how test data is stored into the context, so
that individual steps can read or update that data; thus `expect-success` knows that a prior step
has recorded a :response key into the context, and `expect-data` can use that same :response key.

## Creating Steps

Each step function takes a `context` map as its only parameter, and then
invokes `p/continue` to continue to the next step.
Obviously, a real step function will do something useful first, such as override a component in a component system,
or redefine a function with a mock, make an assertion with `clojure.test/is`, or anything else that's needed.

For example, the `start-system` step from the above example is coded as:
 
```clojure
(defn start-system
  [context]
  (let [system (-> context :system component/start-system)]
     (try
       (p/continue (assoc context :system system)
       (finally
         (component/stop-system system))))))
```

This is a function that accepts a context, operates on it, and passes it to the
`continue` function (which, in turn, finds the next step function, and passes the context to
that).  The `try` ensures that the system is stopped, regardless of what happens 
in later steps. 

Often, a step requires data specific to a particular test; in that case, a step function builder can create a
step function that is passed to `p/execute`.
For example, the `expect-data` function isn't a step itself; it is a builder that returns a step function:

```clojure
(defn expect-data
   [data]
   (fn [context]
     (is (= data (get-in context [:response :body :data])))
     (p/continue context)))
```

Although the step function returned by `expect-data` is typically the final step in the pipeline, it should still
call `continue` just in case it isn't.  For example, a test might use `expect-data` to assert an expected
response, but then have further steps to make other assertions, such as checking how data was persisted to a database.

The `execute` function will throw an exception if the final step function never gets invoked (due
to a prior step not calling `continue`), as this is almost certainly a bug in the step function
implementation.

## then

The `then` macro is a step builder that evaluates to a step function that itself
evaluates some expressions before continuing; 
this is handy for performing some assertions when the context is not directly needed, or triggering some
code for side effects.

```clojure
   ...
   (p/then (is (= 42 (calculate-final-answer))))
```

## is

The above example has its own special case and can be rewritten as:

```clojure
   ...
   (p/is (= 42 (calculate-final-answer)))
```

## testing

A final `clojure.test` helper is `testing`, which is just a wrapper around `clojure.test/testing`.

```clojure
   ...
   (p/testing "Deep Thought")
   (p/is (= 42 (calculate-final-answer)))
```

"Deep Thought" will be the innermost test context (in `clojure.test/*testing-contexts*`) when checking the
result of `calculate-final-answer`.

## Halting

The pipeline execution can be terminated with `halt`; `halt` exists to avoid the above check that all
steps executed.

This is useful when an early failure (say, an incorrect HTTP response from a server)
will lead to a crowd of meaningless failures further on (such as validating
the HTTP response).

The function `halt-on-failure` is often more useful, it ensures that after
any step where test failures or errors occur, the pipeline execution is terminated.
Typically, this is often used when initially developing the code and tests, but can
be discarded once everything is stable.

## What's in the box?

The library itself is quite small; here's the key functions and macros:

- `execute` is the primary entrypoint
- `continue` is invoked by a step function to continue to the next step
- `mock` is used to override a function with a mock implementation
- `spy` is used to capture arguments passed to a function, and optionally mock it at the same time
- `calls` is used to obtain the captured arguments to a spied function
- `update-in-context` and `assoc-in-context` are used to modify the context during execution
- `capture-logging` captures log events; a wrapper around `clojure.tools.logging.test/with-log`
- `halt` terminates pipeline execution
- `halt-on-failure` terminates execution if any test failures occur
- `then` evaluate expressions during execution
- `is`, `context-is`, `testing` wrappers around clojure.test
- `cleanup`  expressions execute after pipeline execution completes
- `providing`, `validating` provide more powerful mocking capabilities, care of [nubank/mockfn](https://github.com/nubank/mockfn)
- `matches?` which builds on [matcher-combinators](https://github.com/nubank/matcher-combinators)

Please refer to the [API documentation](https://cljdoc.org/d/io.github.hlship/test-pipeline) for more details.

## License

Copyright © 2022-Present Howard Lewis Ship

Distributed under the Apache License, Version 2.0.



