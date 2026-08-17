(ns cache.lru-test
  (:require [clojure.test :refer [deftest]]
            [cache.lru]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

(q/defdriver cache
  {:spec "spec/lru.qnt"
   :main "lruTest"
   :scan '[cache.lru]})

(deftest lru-cache-conforms-to-spec
  (qt/check cache {:traces 200 :max-steps 25}))
