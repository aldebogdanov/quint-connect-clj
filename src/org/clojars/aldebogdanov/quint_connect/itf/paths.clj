(ns org.clojars.aldebogdanov.quint-connect.itf.paths
  "The half of decoding that the driver steers: reading a decoded state through
  `:state-path`, `:action-path` and `:nondet-path`.

  Nothing here sees JSON. `itf` turns ITF into values and hands each decoded
  state over; these functions decide where in it the action, the picks and the
  state to compare are, and say so loudly when a path leads nowhere. A path is
  about the spec's own variables, which is why it can be wrong in ways the ITF
  never is."
  (:require [clojure.string :as str]))

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- path!
  "A decode path is a vector of keys for `get-in`, and nothing else. A bare
  keyword is the likely mistake, so it gets said out loud."
  [opt path]
  (when-not (vector? path)
    (fail :bad-decode-path
          (str opt " must be a vector of keys, as in [:lastAction]; got " (pr-str path))
          {:option opt :path path}))
  path)

(defn paths!
  "The decode paths in a driver's options, checked.

  Takes the options map, of which it reads `:state-path`, `:action-path` and
  `:nondet-path`. Returns a map of those that are set. Throws `ex-info` with
  `:quint/error` `:bad-decode-path` for one that is not a vector."
  [opts]
  (reduce (fn [m k] (cond-> m (get opts k) (assoc k (path! k (get opts k)))))
          {} [:state-path :action-path :nondet-path]))

(defn- action-at
  "The action name a spec recorded for itself, from an ordinary variable."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (string? v) v
      (nil? v)    (fail :bad-decode-path
                        (str ":action-path " path " found nothing in state " index)
                        {:option :action-path :path path :index index
                         :vars (vec (sort (keys state)))})
      :else       (fail :bad-decode-path
                        (str ":action-path " path " found " (pr-str v) " in state " index
                             ", which is not an action name."
                             (when (:tag v) " For a sum type, end the path in :tag."))
                        {:option :action-path :path path :index index :found v}))))

(defn- picks-at
  "The picks a spec recorded for itself. Not unwrapped: `Some`/`None` is
  Quint's own encoding of `mbt::nondetPicks`, not something a spec author
  writes into an ordinary variable.

  The empty tuple is no picks. A variant without an argument — Choreo's `Init`
  — carries it as its value, and it is Quint's spelling of nothing rather than
  a malformed record."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (map? v) v
      (= [] v) {}
      :else    (fail :bad-decode-path
                     (str ":nondet-path " path " found " (pr-str v) " in state " index
                          ", and picks must be a record of pick name to value")
                     {:option :nondet-path :path path :index index :found v}))))

(defn- state-at
  "The record a spec keeps all of its state in — Choreo's `s` — whose fields
  stand in for spec variables from here on."
  [{:keys [index state]} path]
  (let [v (get-in state path)]
    (cond
      (map? v) v
      (nil? v) (fail :bad-decode-path
                     (str ":state-path " path " found nothing in state " index
                          "; the trace's variables are "
                          (str/join ", " (map pr-str (sort (keys state)))))
                     {:option :state-path :path path :index index
                      :vars (vec (sort (keys state)))})
      :else    (fail :bad-decode-path
                     (str ":state-path " path " found " (pr-str v) " in state " index
                          ", and the state to compare must be a record")
                     {:option :state-path :path path :index index :found v}))))

(defn- roots
  "The state variables the given paths read out of."
  [& paths]
  (into [] (comp (remove nil?) (map first)) paths))

(defn- something-left!
  "Refuse paths whose roots take every variable with them. The action is read
  correctly and then nothing is compared, so every step passes — which is what
  `:action-path [:s ...]` does to a Choreo spec, whose `s` is all of its state."
  [{:keys [index state]} split set-opts]
  (when (and (seq state) (every? (set split) (keys state)))
    (fail :bad-decode-path
          (str (str/join " and " set-opts) (if (next set-opts) " start" " starts") " at "
               (str/join ", " (map pr-str (distinct split)))
               ", which is every variable in state " index
               ", so nothing would be left to compare. When one variable holds"
               " all of the state, as Choreo's s does, set :state-path [:s] and"
               " write the other paths inside it.")
          {:option (first set-opts) :index index :roots (vec (distinct split))})))

(defn tracked
  "One decoded state, read through the driver's paths.

  Takes the state as `itf` decodes it — `{:index :action :picks :state}` — and
  the map `paths!` returns. Returns the same state with `:action` and `:picks`
  taken from ordinary state variables, for traces that carry no `mbt::`
  metadata — `quint test` and `quint verify` emit none, and a Choreo-style spec
  tracks them itself. Each path's root variable leaves `:state`: it is the
  spec's own bookkeeping, and the implementation must not be asked to supply
  it.

  `:state-path` goes first. The state becomes the record found there, and the
  other two paths are read inside it, so their roots are its fields.

  Throws `ex-info` with `:quint/error` `:bad-decode-path` when a path does not
  lead to a record of state, an action name or a record of picks, or when the
  paths' roots would leave nothing to compare."
  [st {:keys [state-path action-path nondet-path]}]
  (let [st    (cond-> st state-path (assoc :state (state-at st state-path)))
        split (roots action-path nondet-path)]
    (something-left! st split (cond-> [] action-path (conj :action-path)
                                     nondet-path (conj :nondet-path)))
    (cond-> st
      action-path (assoc :action (action-at st action-path))
      nondet-path (assoc :picks (picks-at st nondet-path))
      :always     (update :state #(apply dissoc % split)))))
