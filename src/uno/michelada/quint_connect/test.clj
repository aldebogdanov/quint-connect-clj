(ns uno.michelada.quint-connect.test
  "clojure.test integration: one assertion carrying the whole failure."
  (:require [clojure.test :as t]
            [uno.michelada.quint-connect.core :as core]
            [uno.michelada.quint-connect.report :as report]))

(defn check
  "Run `core/check` and assert the result, so a divergence fails the enclosing
  `deftest` with the step, action, handler var, diff and reproduce line as its
  message. Returns the result map, so a test may assert further on it."
  [driver opts]
  (let [r (core/check driver opts)]
    (t/is (:ok? r) (report/result-str r))
    r))
