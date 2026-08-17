(ns org.clojars.aldebogdanov.quint-connect.replay
  "Run one decoded trace against a resolved driver. No I/O of its own: the only
  effects are the ones the driver's own functions perform."
  (:require [clojure.data :as data]))

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- read-state
  "Call every reader and merge the partial state maps they return. Yields
  [state provenance], where provenance maps each spec variable to the var of
  the reader that supplied it — taken from what the readers actually returned,
  so a `:*` reader needs to declare nothing."
  [readers]
  (reduce
   (fn [[state prov] {f :fn v :var}]
     (let [m (try (f)
                  (catch Exception e
                    (fail :state-read-failed
                          (str "state reader threw" (when v (str ": " v)))
                          {:reader v :cause e})))]
       (when-not (map? m)
         (fail :state-read-failed
               (str "state reader did not return a map" (when v (str ": " v)))
               {:reader v :returned m}))
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

    {:actions {\"deposit\" {:fn f :var v}}   f takes the picks map
     :readers [{:fn f :var v}]              f takes no arguments -> partial state
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
  `:unknown-action`, `:anonymous-action`, `:state-read-failed`."
  [driver trace]
  (try
    (replay-states driver (:states trace))
    (finally
      (when-let [h (:halt driver)] ((:fn h))))))
