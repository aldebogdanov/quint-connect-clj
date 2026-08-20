(ns org.clojars.aldebogdanov.quint-connect.replay
  "Run one decoded trace against a resolved driver. No I/O of its own: the only
  effects are the ones the driver's own functions perform."
  (:require [clojure.data :as data]
            [clojure.string :as str]))

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- reader-name
  "How to name a reader in a message. A driver-map `:state` entry has no var."
  [v]
  (if v (str v) "an entry in the driver map's :state"))

(defn- read-state
  "Call every reader and merge the partial state maps they return. Yields
  [state provenance], where provenance maps each spec variable to the var of
  the reader that supplied it — taken from what the readers actually returned,
  so a `:*` reader needs to declare nothing.

  Two readers supplying one variable is `:duplicate-state`. The registry
  catches that at construction for a reader that names its variable; a `:*`
  reader names nothing, so what it covers is only knowable here, after it has
  been called. Readers marked `:override?` are exempt and come last: replacing
  a reader is what a driver-map `:state` entry is for.

  Throws `:state-read-failed` and `:duplicate-state`."
  [readers]
  (reduce
   (fn [[state prov] {f :fn v :var override? :override?}]
     (let [m (try (f)
                  (catch Exception e
                    (fail :state-read-failed
                          (str "state reader threw" (when v (str ": " v)))
                          {:reader v :cause e})))]
       (when-not (map? m)
         (fail :state-read-failed
               (str "state reader did not return a map" (when v (str ": " v)))
               {:reader v :returned m}))
       (when-not override?
         (doseq [k (keys m)
                 :when (contains? prov k)]
           (fail :duplicate-state
                 (str "two state readers both supply " (pr-str k) ": "
                      (reader-name (get prov k)) " and " (reader-name v)
                      ". A :* reader covers whatever it returns, which is why"
                      " this is not caught at driver construction.")
                 {:name k :vars [(get prov k) v]})))
       [(merge state m) (reduce #(assoc %1 %2 v) prov (keys m))]))
   [{} {}] readers))

(defn- dispatch
  "The handler for this step, as [handler kind]. Step 0 goes to an `init`
  annotation unless an action handler claims the name outright. The kinds are
  called differently: an action takes the picks map, `init` takes nothing."
  [{:keys [actions init]} action first?]
  (cond
    (contains? actions action) [(get actions action) :action]
    first?                     (if init
                                 [init :init]
                                 (fail :no-init
                                       (str "trace starts with action " (pr-str action)
                                            " and nothing is annotated :quint/init")
                                       {:action action}))
    (= "" action)              (fail :anonymous-action
                                     "the spec took an anonymous action; name the combined action in the spec"
                                     {:known (set (keys actions))})
    (nil? action)              (fail :unknown-action
                                     (str "trace carries no action: generate it with quint run --mbt,"
                                          " or point :action-path at the variable the spec records it in")
                                     {:known (set (keys actions))})
    :else                      (fail :unknown-action
                                     (str "no handler for action " (pr-str action))
                                     {:action action :known (set (keys actions))})))

(defn- needed-picks!
  "Check that every pick this handler needs is in this step's picks.

  Both sources are held to the trace: names written into `:quint/args`, and
  names derived from the parameter list, since a parameter misspelled against
  the spec binds nil exactly as a misspelled annotation does. The keyword says
  which one to go and fix.

  A pick name beginning with `_` is never checked. That is Clojure's way of
  saying a parameter is unused, and getting-started §3 already sanctions it as
  the way to ignore a pick, so the exemption encodes a documented rule rather
  than carving one out.

  Throws `:bad-args` for a name from `:quint/args`, `:bad-arglist` for one from
  the parameter list."
  [{:keys [needs] from :needs-from var- :var} action picks]
  (when-some [missing (seq (remove #(contains? picks %) needs))]
    (let [named (str/join ", " (map pr-str (sort missing)))
          has   (if (seq picks)
                  (str/join ", " (map pr-str (sort (keys picks))))
                  "no picks at all")]
      (if (= :arglist from)
        (fail :bad-arglist
              (str (or var- (str "the handler for " (pr-str action)))
                   " takes a parameter named for the pick " named
                   ", which this trace does not carry. It has " has
                   ". Rename the parameter, give the real order in :quint/args,"
                   " or prefix it with _ if the handler does not need it.")
              {:action action :var var- :missing (vec (sort missing))
               :picks (vec (sort (keys picks)))})
        (fail :bad-args
              (str from " for action " (pr-str action) " names " named
                   ", which this trace does not carry. It has " has ".")
              {:action action :var var- :missing (vec (sort missing))
               :picks (vec (sort (keys picks)))})))))

(defn- mismatch
  "The diverging part of one comparison, or nil. Only the trace's own variables
  are compared: a reader supplying extra keys is not an error, while a spec
  variable nothing supplies diverges here against nothing, on the first state
  that carries it. The driver never sees the spec, so that first comparison is
  the earliest anything can know the variable exists."
  [{:keys [ignore] cmp :compare} expected actual]
  (let [diverged (remove (fn [k]
                           (or (contains? (or ignore #{}) k)
                               (if-let [eq (get cmp k)]
                                 (eq (get expected k) (get actual k))
                                 (= (get expected k) (get actual k)))))
                         (keys expected))]
    (when (seq diverged)
      {:expected (select-keys expected diverged)
       :actual   (select-keys actual diverged)})))

(defn- result [driver used steps failure]
  {:ok?      (nil? failure)
   :steps    steps
   :coverage {:used   used
              :unused (into #{} (remove (set (keys used))) (keys (:actions driver)))}
   :failure  failure})

(defn- replay-states [driver states]
  (loop [[st & more] states, used {}, steps 0]
    (if-not st
      (result driver used steps nil)
      (let [{:keys [index action picks]} st
            [h kind] (dispatch driver action (zero? steps))
            _ (when (= :action kind) (needed-picks! h action picks))
            cause (try (if (= :init kind) ((:fn h)) ((:fn h) picks))
                       nil
                       (catch Exception e e))]
        (if cause
          (result driver used (inc steps)
                  {:step index :action action :picks picks :handler (:var h)
                   :expected (:state st) :actual nil :readers {} :diff nil :cause cause})
          (let [[actual prov] (read-state (:readers driver))
                mm            (mismatch driver (:state st) actual)]
            (if mm
              (result driver used (inc steps)
                      (assoc mm
                             :step index :action action :picks picks :handler (:var h)
                             :readers (select-keys prov (keys (:expected mm)))
                             :diff (data/diff (:expected mm) (:actual mm))
                             :cause nil))
              (recur more
                     (cond-> used (= :action kind) (update action (fnil inc 0)))
                     (inc steps)))))))))

(defn run-trace
  "Replay one decoded trace against a resolved driver.

  The driver is data:

    {:actions {\"deposit\" {:fn f :var v :needs #{:who} :needs-from :arglist}}
                                            f takes the picks map; :needs are the
                                            picks the trace must carry, and
                                            :needs-from says which annotation to
                                            blame when it does not
     :readers [{:fn f :var v}]              f takes no arguments -> partial state
                                            :override? true exempts it from the
                                            duplicate check, for driver-map state
     :init    {:fn f :var v}                optional, takes nothing; before step 0
     :halt    {:fn f :var v}                optional, run in a finally
     :ignore  #{:lastError}                 spec variables not compared
     :compare {:balances (fn [expected actual] ...)}}

  The trace is `itf/itf->trace` output. Returns

    {:ok? true :steps 5 :coverage {:used {...} :unused #{...}} :failure nil}

  Replay stops at the first diverging step, whose `:failure` carries the step
  index, action, picks, the handler and reader vars, the diverging part of both
  states, a `clojure.data/diff`, and `:cause` when a handler threw. Divergence
  is a value, not an exception.

  Throws `ex-info` with `:quint/error` for a broken setup: `:no-init`,
  `:unknown-action`, `:anonymous-action`, `:state-read-failed`,
  `:duplicate-state`, `:bad-args`, `:bad-arglist`."
  [driver trace]
  (try
    (replay-states driver (:states trace))
    (finally
      (when-let [h (:halt driver)] ((:fn h))))))
