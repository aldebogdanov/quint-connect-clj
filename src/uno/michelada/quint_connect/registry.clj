(ns uno.michelada.quint-connect.registry
  "Turn `:quint/*` metadata into the resolved driver replay consumes. The only
  namespace that reflects, and only over the explicit `:scan` list. Rebuilt on
  every call: no global registry, nothing cached between runs."
  (:require [clojure.string :as str]))

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

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
  [v m args-key]
  (or (get m args-key)
      (let [arglists (:arglists (meta v))]
        (if (= 1 (count arglists))
          (mapv keyword (first arglists))
          (fail :ambiguous-arity
                (str v " has " (count arglists) " arities; add :quint/args to say"
                     " which picks map to which argument")
                {:var v :arglists arglists})))))

(defn- action-handler [v m args-key]
  (let [args (picks->args v m args-key)]
    {:fn (fn [picks] (apply @v (map picks args))) :var v}))

(defn- reader
  "A state reader as replay wants it: no arguments, returns a partial state map.
  An `IDeref` var is dereferenced, a function var is called, `:path` is applied
  after either, and `:*` means the value already is the state map."
  [v spec]
  (let [raw   (if (instance? clojure.lang.IDeref (deref v))
                (fn [] (deref (deref v)))
                (fn [] ((deref v))))
        {:keys [path] var-name :var} (if (map? spec) spec {:var spec})
        value (if (seq path) (fn [] (get-in (raw) path)) raw)]
    (if (= :* var-name)
      {:fn raw :var v :supplies :*}
      {:fn (fn [] {var-name (value)}) :var v :supplies var-name})))

(defn- lifecycle
  "An `init` or `halt` entry as replay wants it: called with no arguments."
  [v]
  {:fn (fn [] ((deref v))) :var v})

(defn- scan-ns
  "Everything one namespace declares, as four vectors. `:inits` and `:halts`
  are vectors rather than single entries so that two vars claiming the same
  lifecycle role can be counted and rejected, here or across the scan."
  [key-ns ns-sym]
  (require ns-sym)
  (let [[ka kg ks ki kh] (map #(annotation-key key-ns %) [:action :args :state :init :halt])
        found (reduce
               (fn [acc [_ v]]
                 (let [m (var-meta v)]
                   (cond-> acc
                     (contains? m ka) (update :actions conj [(get m ka) (action-handler v m kg)])
                     (contains? m ks) (update :readers conj (reader v (get m ks)))
                     (get m ki)       (update :inits conj (lifecycle v))
                     (get m kh)       (update :halts conj (lifecycle v)))))
               {:actions [] :readers [] :inits [] :halts []}
               (ns-interns ns-sym))]
    (when (every? empty? (vals found))
      (fail :empty-scan
            (str ns-sym " is in :scan but carries no " key-ns "/* annotations."
                 " The usual cause is metadata on the (defn ...) form, which"
                 " Clojure discards: write (defn ^{...} name ...) or"
                 " (defn name {...} ...) instead.")
            {:ns ns-sym :key-ns key-ns}))
    found))

(defn- no-duplicates! [error what pairs]
  (doseq [[k group] (group-by first pairs)
          :when (< 1 (count group))]
    (fail error
          (str "two vars claim the same " what " " (pr-str k) ": "
               (str/join ", " (sort (map #(str (:var (second %))) group))))
          {:name k :vars (mapv #(:var (second %)) group)})))

(defn- only
  "The single lifecycle annotation of its kind in the whole scan, or nil.

  Two of them is an error rather than first-one-wins. The loser would simply
  never run, and an `init` that never runs is the worst shape a failure can
  take here: it surfaces as a divergence in some later trace, at a step that
  has nothing to do with the var that was skipped."
  [error k found]
  (when (< 1 (count found))
    (fail error
          (str "two vars claim " k ": "
               (str/join ", " (sort (map #(str (:var %)) found))))
          {:name k :vars (mapv :var found)}))
  (first found))

(defn- normalize-actions
  "Driver-map actions are bare functions of the picks map and win over the scan."
  [m]
  (reduce-kv (fn [acc k f] (assoc acc k {:fn f :var nil})) {} m))

(defn- normalize-state [m]
  (mapv (fn [[k f]] {:fn (fn [] {k (f)}) :var nil :supplies k}) m))

(defn resolve-driver
  "Build the resolved driver from a driver map.

  Reads `:scan` (a vector of namespace symbols, required'd then reflected over)
  under the qualifier in `:key-ns`, a symbol defaulting to `quint`. Explicit
  `:actions` and `:state` in the map are merged on top and win.

  Returns the map `replay/run-trace` consumes — `:actions`, `:readers`,
  `:init`, `:halt`, `:ignore`, `:compare` — with the remaining keys of the
  driver map (`:spec`, `:main`, `:init-action`, `:step-action`, `:action-path`,
  `:nondet-path`, `:key-fn`, …) passed through untouched for the caller.

  Throws `ex-info` with `:quint/error` `:empty-scan`, `:duplicate-action`,
  `:duplicate-state`, `:duplicate-init`, `:duplicate-halt` or
  `:ambiguous-arity`."
  [{:keys [scan key-ns actions state] :or {key-ns 'quint} :as m}]
  (let [scanned    (map #(scan-ns key-ns %) scan)
        act-pairs  (mapcat :actions scanned)
        readers    (mapcat :readers scanned)
        overridden (set (keys state))]
    (no-duplicates! :duplicate-action "action" act-pairs)
    (no-duplicates! :duplicate-state "state variable"
                    (keep (fn [r] (when (not= :* (:supplies r)) [(:supplies r) r])) readers))
    (let [init (only :duplicate-init (annotation-key key-ns :init) (mapcat :inits scanned))
          halt (only :duplicate-halt (annotation-key key-ns :halt) (mapcat :halts scanned))]
      (-> (dissoc m :scan :state)
          (assoc :actions (merge (into {} act-pairs) (normalize-actions actions))
                 :readers (into (vec (remove #(contains? overridden (:supplies %)) readers))
                                (normalize-state state))
                 :key-ns  key-ns)
          (cond-> init (assoc :init init)
                  halt (assoc :halt halt))))))
