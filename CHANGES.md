# 0.6 -- 19 Apr 2023

Added `cleanup`.  Added dependency on [nubank/mockfn](https://github.com/nubank/mockfn) and added
`providing` and `verifying`.

# 0.5.1 -- 18 Apr 2023

Added two-arity version of `is` to match two-arity `clojure.test/is`.

# 0.5 -- 17 Apr 2023

Added `then`, `is`, and `testing`.

# v0.4 -- 5 Jul 2022

Removed `redef` as it was identical to the older `mock`.

# v0.3 -- 16 Mar 2022

Converted to .cljc for use in testing ClojureScript applications.

# v0.2 -- 25 Jan 2022

Added the `bind` and `redef` step function factories.

Changed `capture-logging` to simply invoke `clojure.tools.logging.test/with-log`; removed `log-events`.

[Closed Issues](https://github.com/hlship/test-pipeline/milestone/1?closed=1)

# v0.1 -- 20 Jan 2022

Initial release.
