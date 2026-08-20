(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-shapes
  "Every shape :quint/args composes, each against a handler that takes it back
  apart. Read the annotation and the parameter list side by side: one is the
  inverse of the other.")

(defn pair
  {:quint/action "pair" :quint/args [[:x :y]]}
  [[x y]]
  [x y])

(defn nested-vec
  {:quint/action "nestedVec" :quint/args [[:a [:b :c]]]}
  [[a [b c]]]
  [a b c])

(defn map-with-vec
  {:quint/action "mapWithVec" :quint/args [{:pos [:x :y] :id :i}]}
  [{:keys [pos id]}]
  [pos id])

(defn vec-with-map
  {:quint/action "vecWithMap" :quint/args [[{:a :pa} :b]]}
  [[{:keys [a]} b]]
  [a b])

(defn deep
  {:quint/action "deep" :quint/args [{:outer {:inner [:p [:q :r]]}}]}
  [{{:keys [inner]} :outer}]
  inner)
