(ns org.clojars.aldebogdanov.quint-connect.registry.validation
  "The loud half of the registry: every check that turns an annotation nobody
  would read into an error at driver construction.

  Nothing here reflects. `registry` does the reading — `ns-interns`, `meta`,
  `deref` — and hands over what it found; these functions decide whether it can
  be used and say why not. That split is why the reflection rule still reads
  \"confined to `registry`\" with this namespace underneath it."
  (:require [clojure.string :as str]))

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn pick-names!
  "The pick names an action's arglist declares, in argument order.

  Takes the var (for the message) and its `:arglists`. Returns a vector of
  keywords.

  Every parameter is a pick name, looked up in the picks map by that name, so a
  parameter with no name to look up would arrive as nil on every step — a
  divergence with no cause attached to it, which is the silence this layer
  exists to prevent.

  Throws `:ambiguous-arity` for a multi-arity var, and `:bad-arglist` for a
  destructuring parameter, a rest parameter, or no `:arglists` at all."
  [v arglists]
  (cond
    (= 1 (count arglists))
    (let [arglist (first arglists)]
      (doseq [p arglist]
        (cond
          (= '& p)
          (fail :bad-arglist
                (str v " is variadic, and a rest parameter is not a pick name."
                     " Name the picks in :quint/args, or annotate a wrapper in"
                     " the test namespace that takes them positionally.")
                {:var v :arglist arglist :parameter p})

          (not (simple-symbol? p))
          (fail :bad-arglist
                (str v " destructures " (pr-str p) ", and a destructuring form"
                     " is not a pick name. Two different intentions look like"
                     " this. If it destructures one pick whose value is a"
                     " record, name that pick — :quint/args [:m] — and the"
                     " destructuring applies to its value. If it wants the"
                     " whole picks map, that is the driver map's :actions"
                     " calling convention and not this one.")
                {:var v :arglist arglist :parameter p})))
      (mapv keyword arglist))

    (seq arglists)
    (fail :ambiguous-arity
          (str v " has " (count arglists) " arities; add :quint/args to say"
               " which picks map to which argument")
          {:var v :arglists arglists})

    :else
    (fail :bad-arglist
          (str v " has no :arglists to read pick names from"
               (if (fn? (deref v))
                 (str ", which is what (def f (fn ...)) leaves behind."
                      " Use defn, or name the picks in :quint/args.")
                 (str ", and its value is not a function, so it cannot"
                      " handle an action at all.")))
          {:var v :arglists arglists})))

(defn- template!
  "One `:quint/args` entry, checked. A pick name is a leaf; a vector or a map
  composes one out of others. Recursive, because destructuring is."
  [v args template]
  (cond
    (keyword? template) nil

    (vector? template)
    (run! #(template! v args %) template)

    (map? template)
    (do (when-some [bad (first (remove keyword? (keys template)))]
          (fail :bad-args
                (str v " has the :quint/args key " (pr-str bad) ", and the keys"
                     " of a composed map are the keys the handler destructures,"
                     " which are keywords. Quint records decode with keyword"
                     " keys, so this is the shape that meets them.")
                {:var v :args args :template template :found bad}))
        (run! #(template! v args %) (vals template)))

    :else
    (fail :bad-args
          (str v " has the :quint/args entry " (pr-str template) ", which is"
               " neither a pick name nor a vector or map composing one out of"
               " picks. Write :who, or [:x :y], or {:from :src}"
               (when (set? template)
                 (str ". A set has no positional meaning and nothing"
                      " destructures one, so it is not a shape that can be"
                      " composed here"))
               ".")
          {:var v :args args :template template})))

(defn args!
  "Check an explicit `:quint/args`. Takes the var (for the message) and the
  annotation. Returns it.

  `:quint/args` is a vector, one entry per argument, and each entry is the
  **shape of that argument** with pick names where its values go — the exact
  inverse of the destructuring the handler performs on it:

      handler                        :quint/args
      [who amount]                   [:who :amount]
      [{:keys [from to]}]            [{:from :src :to :dst}]
      [[x y]]                        [[:x :y]]
      [{:keys [pos]}]                [{:pos [:x :y]}]
      [[{:keys [a]} b]]              [[{:a :pa} :b]]

  A pick name is a leaf; vectors and maps nest as deeply as the handler takes
  them apart. Map keys are literal, map values are shapes in their own right.

  Throws `:bad-args`."
  [v args]
  (when-not (vector? args)
    (fail :bad-args
          (str v " has :quint/args " (pr-str args) ", and :quint/args must be a"
               " vector, one entry per argument, as in [:who :amount].")
          {:var v :args args}))
  (run! #(template! v args %) args)
  args)

(def state-keys
  "The keys `:quint/state` reads when it is given a map."
  #{:var :path})

(defn state-spec!
  "What `:quint/state` carries, as a map. Takes the var (for the message) and
  the annotation. A bare keyword names the spec variable; a map may add
  `:path`, a vector of keys applied with `get-in` after the value is read.
  Returns the map form.

  Throws `:bad-state-spec` for anything that would leave part of the annotation
  unread: an unknown key, a missing `:var`, or a `:path` that is not a vector."
  [v spec]
  (let [m (if (map? spec) spec {:var spec})
        {:keys [path] var-name :var} m]
    (when-some [unknown (first (remove state-keys (keys m)))]
      (fail :bad-state-spec
            (str v " is annotated :quint/state with the unknown key "
                 (pr-str unknown) "; only :var and :path are read.")
            {:var v :spec spec :key unknown :supported state-keys}))
    (when-not (keyword? var-name)
      (fail :bad-state-spec
            (str v " is annotated :quint/state " (pr-str spec) ", which names no"
                 " spec variable. Write :quint/state :balances, or"
                 " :quint/state {:var :balances :path [:inner]}.")
            {:var v :spec spec}))
    (when (and (some? path) (not (vector? path)))
      (fail :bad-state-spec
            (str v " has :quint/state :path " (pr-str path) ", and a path must"
                 " be a vector of keys, as in [:inner].")
            {:var v :spec spec :path path}))
    m))

(defn annotated!
  "Assert that a scanned namespace carried annotations at all. Takes the
  namespace symbol, the qualifier, and what the scan found. Returns nil.

  Throws `:empty-scan`, naming the `^{...} (defn ...)` trap, which is silent in
  Clojure itself and is what this is nearly always about."
  [ns-sym key-ns annotated]
  (when (empty? annotated)
    (fail :empty-scan
          (str ns-sym " is in :scan but carries no " key-ns "/* annotations."
               " The usual cause is metadata on the (defn ...) form, which"
               " Clojure discards: write (defn ^{...} name ...) or"
               " (defn name {...} ...) instead.")
          {:ns ns-sym :key-ns key-ns})))

(defn matchable!
  "Assert that no annotation is scoped to a driver this one cannot be matched
  against. Takes the namespace symbol, the qualifier, the driver key as
  written, and the scoped vars the scan found. Returns nil.

  A scoped annotation asks a question an unnamed driver cannot answer, and
  guessing it either way is the silence this design refuses.

  Throws `:unnamed-driver`."
  [ns-sym key-ns driver-key scoped]
  (when (seq scoped)
    (fail :unnamed-driver
          (str (first scoped) " is scoped with " driver-key
               " but this driver has no :name, so there is nothing to match"
               " it against. Give the driver map a :name, or drop the scope.")
          {:ns ns-sym :vars (vec scoped) :key-ns key-ns})))

(defn no-duplicates!
  "Assert that no two vars claim the same name. Takes the error keyword, the
  word for what is claimed, and `[name entry]` pairs whose entries carry a
  `:var`. Returns nil. Throws the given error, naming every var involved."
  [error what pairs]
  (doseq [[k group] (group-by first pairs)
          :when (< 1 (count group))]
    (fail error
          (str "two vars claim the same " what " " (pr-str k) ": "
               (str/join ", " (sort (map #(str (:var (second %))) group))))
          {:name k :vars (mapv #(:var (second %)) group)})))

(defn only
  "The single lifecycle annotation of its kind in the whole scan, or nil.

  Two of them is an error rather than first-one-wins. The loser would simply
  never run, and an `init` that never runs is the worst shape a failure can
  take here: it surfaces as a divergence in some later trace, at a step that
  has nothing to do with the var that was skipped.

  Takes the error keyword, the annotation key as written, and the entries
  found. Throws the given error, naming both vars."
  [error k found]
  (when (< 1 (count found))
    (fail error
          (str "two vars claim " k ": "
               (str/join ", " (sort (map #(str (:var %)) found))))
          {:name k :vars (mapv :var found)}))
  (first found))
