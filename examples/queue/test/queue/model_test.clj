(ns queue.model-test
  (:require [clojure.test :refer [deftest]]
            [queue.core]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.test :as qt]))

;; Same :spec, same :scan. What differs is :main — which module of the spec —
;; and :name, which decides whose :quint/init is read.
(q/defdriver small
  {:name :small :spec "spec/queue.qnt" :main "smallQueue" :scan '[queue.core]})

(q/defdriver large
  {:name :large :spec "spec/queue.qnt" :main "largeQueue" :scan '[queue.core]})

(deftest queue-of-two-conforms-to-spec
  (qt/check small {:traces 30 :max-steps 15}))

(deftest queue-of-five-conforms-to-spec
  (qt/check large {:traces 30 :max-steps 15}))
