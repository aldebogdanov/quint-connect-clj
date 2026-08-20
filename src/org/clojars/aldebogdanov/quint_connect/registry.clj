(ns org.clojars.aldebogdanov.quint-connect.registry
  "Turn `:quint/*` metadata into the resolved driver replay consumes. The only
  namespace that reflects, and only over the explicit `:scan` list. Rebuilt on
  every call: no global registry, nothing cached between runs.

  What it reads is decided here; whether what it read can be used is decided in
  `registry.validation`, which reflects over nothing."
  (:require [org.clojars.aldebogdanov.quint-connect.registry.validation :as v]))

(defn- annotation-key [key-ns n]
  (keyword (str key-ns) (name n)))

(defn- var-meta
  "Metadata from the var and, when it holds an atom/ref/agent, from the
  reference object too. The var wins."
  [v]
  (let [value (deref v)]
    (merge (when (instance? clojure.lang.IReference value) (meta value))
           (meta v))))

(defn- picks->args
  "Pick names in argument order: `:quint/args` if given, else the sole arglist."
  [var- m args-key]
  (or (get m args-key)
      (v/pick-names! var- (:arglists (meta var-)))))

(defn- action-handler [var- m args-key]
  (let [args (picks->args var- m args-key)]
    {:fn (fn [picks] (apply @var- (map picks args))) :var var-}))

(defn- reader
  "A state reader as replay wants it: no arguments, returns a partial state map.
  An `IDeref` var is dereferenced, a function var is called, and `:path` is
  applied with `get-in` after either.

  `:*` means the value already is the state map — and takes `:path` too, since
  a whole state map is as likely to sit nested inside a system map as a single
  variable is."
  [var- spec]
  (let [{:keys [path] var-name :var} (v/state-spec! var- spec)
        raw   (if (instance? clojure.lang.IDeref (deref var-))
                (fn [] (deref (deref var-)))
                (fn [] ((deref var-))))
        value (if (seq path) (fn [] (get-in (raw) path)) raw)]
    (if (= :* var-name)
      {:fn value :var var- :supplies :*}
      {:fn (fn [] {var-name (value)}) :var var- :supplies var-name})))

(defn- lifecycle
  "An `init` or `halt` entry as replay wants it: called with no arguments."
  [var-]
  {:fn (fn [] ((deref var-))) :var var-})

(defn- claims
  "The drivers an annotation names, as a set, or nil when it names none. A
  keyword or a collection of them; nil is the ordinary case and means every
  driver."
  [m kd]
  (when-some [d (get m kd)]
    (if (coll? d) (set d) #{d})))

(defn- mine?
  "Does this var's annotation apply to the driver being built? Unscoped
  annotations apply to all of them, which is what keeps every annotation
  written before `:quint/driver` existed working untouched."
  [m kd driver-name]
  (let [only (claims m kd)]
    (or (nil? only) (contains? only driver-name))))

(defn- scan-ns
  "Everything one namespace declares for this driver, as four vectors.

  `:inits` and `:halts` are vectors rather than single entries so that two vars
  claiming the same lifecycle role can be counted and rejected, here or across
  the scan. Vars scoped to another driver by `:quint/driver` are dropped before
  that counting, which is the whole point of the key: two inits for two
  different specs are not a collision, and two for the same one still are."
  [key-ns driver-name ns-sym]
  (require ns-sym)
  (let [[ka kg ks ki kh kd]
        (map #(annotation-key key-ns %) [:action :args :state :init :halt :driver])

        annotated (filter (fn [[_ var-]]
                            (let [m (var-meta var-)]
                              (or (contains? m ka) (contains? m ks)
                                  (get m ki) (get m kh))))
                          (ns-interns ns-sym))]
    (v/annotated! ns-sym key-ns annotated)
    (when (nil? driver-name)
      (v/matchable! ns-sym key-ns kd
                    (keep (fn [[_ var-]] (when (get (var-meta var-) kd) var-)) annotated)))
    (reduce
     (fn [acc [_ var-]]
       (let [m (var-meta var-)]
         (cond-> acc
           (contains? m ka) (update :actions conj [(get m ka) (action-handler var- m kg)])
           (contains? m ks) (update :readers conj (reader var- (get m ks)))
           (get m ki)       (update :inits conj (lifecycle var-))
           (get m kh)       (update :halts conj (lifecycle var-)))))
     {:actions [] :readers [] :inits [] :halts []}
     (filter (fn [[_ var-]] (mine? (var-meta var-) kd driver-name)) annotated))))

(defn- normalize-actions
  "Driver-map actions are bare functions of the picks map and win over the scan."
  [m]
  (reduce-kv (fn [acc k f] (assoc acc k {:fn f :var nil})) {} m))

(defn- normalize-state
  "Driver-map state entries are 0-arg functions of one spec variable. They are
  marked as overrides: replay exempts them from the duplicate check a `:*`
  reader can only be caught by there, because overriding a reader is exactly
  what they are for."
  [m]
  (mapv (fn [[k f]] {:fn (fn [] {k (f)}) :var nil :supplies k :override? true}) m))

(defn resolve-driver
  "Build the resolved driver from a driver map.

  Reads `:scan` (a vector of namespace symbols, required'd then reflected over)
  under the qualifier in `:key-ns`, a symbol defaulting to `quint`. Explicit
  `:actions` and `:state` in the map are merged on top and win.

  `:name` names this driver, and only matters when a scanned namespace serves
  more than one: a var annotated `:quint/driver :ledger` is read by the driver
  named `:ledger` and ignored by the others, while an unannotated var is read
  by all of them. A scoped annotation with no `:name` to match is
  `:unnamed-driver`.

  Returns the map `replay/run-trace` consumes — `:actions`, `:readers`,
  `:init`, `:halt`, `:ignore`, `:compare` — with the remaining keys of the
  driver map (`:spec`, `:main`, `:init-action`, `:step-action`, `:action-path`,
  `:nondet-path`, `:key-fn`, …) passed through untouched for the caller.

  Throws `ex-info` with `:quint/error` `:empty-scan`, `:unnamed-driver`,
  `:duplicate-action`, `:duplicate-state`, `:duplicate-init`,
  `:duplicate-halt`, `:ambiguous-arity`, `:bad-arglist` or `:bad-state-spec`.

  Two readers that each name one variable are `:duplicate-state` here. A `:*`
  reader names nothing, so an overlap involving one is only knowable once it
  has been called, and `replay/run-trace` raises it instead."
  [{:keys [scan key-ns actions state] driver-name :name :or {key-ns 'quint} :as m}]
  (let [scanned    (map #(scan-ns key-ns driver-name %) scan)
        act-pairs  (mapcat :actions scanned)
        readers    (mapcat :readers scanned)
        overridden (set (keys state))]
    (v/no-duplicates! :duplicate-action "action" act-pairs)
    (v/no-duplicates! :duplicate-state "state variable"
                      (keep (fn [r] (when (not= :* (:supplies r)) [(:supplies r) r])) readers))
    (let [init (v/only :duplicate-init (annotation-key key-ns :init) (mapcat :inits scanned))
          halt (v/only :duplicate-halt (annotation-key key-ns :halt) (mapcat :halts scanned))]
      (-> (dissoc m :scan :state)
          (assoc :actions (merge (into {} act-pairs) (normalize-actions actions))
                 :readers (into (vec (remove #(contains? overridden (:supplies %)) readers))
                                (normalize-state state))
                 :key-ns  key-ns)
          (cond-> init (assoc :init init)
                  halt (assoc :halt halt))))))
