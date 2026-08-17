(ns myapp.model-test
  (:require [clojure.test :refer [deftest]]
            [myapp.core]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(q/defdriver counter
  {:spec "spec/counter.qnt"
   :scan '[myapp.core]})           ; namespaces to read annotations from

(deftest counter-conforms-to-spec
  (qt/check counter {:traces 10 :max-steps 15}))
