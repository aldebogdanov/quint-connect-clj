(ns org.clojars.aldebogdanov.quint-connect.core
  "The public API: build a driver, then check an implementation against a spec."
  (:require [org.clojars.aldebogdanov.quint-connect.itf :as itf]
            [org.clojars.aldebogdanov.quint-connect.quint :as quint]
            [org.clojars.aldebogdanov.quint-connect.registry :as registry]
            [org.clojars.aldebogdanov.quint-connect.replay :as replay]))

(defn driver
  "Resolve a driver map into the driver `check` and `replay-file` consume.
  See `registry/resolve-driver` for the keys and the errors it throws."
  [m]
  (registry/resolve-driver m))

(defmacro defdriver
  "`(def name (driver m))`, and nothing more."
  [name m]
  `(def ~name (driver ~m)))

(defn- coverage [driver used]
  {:used   used
   :unused (into #{} (remove (set (keys used))) (keys (:actions driver)))})

(defn- decode-opts
  "The driver keys the decoder understands. Everything else in the driver map
  is none of its business."
  [driver]
  (select-keys driver [:key-fn :action-path :nondet-path]))

(defn- replay-json [driver json]
  (replay/run-trace driver (itf/itf->trace (itf/json->itf json) (decode-opts driver))))

(defn- replay-all
  "Replay every generated trace, stopping at the first that diverges, and
  aggregate the coverage and the step count. Takes what `quint/run!` and
  `quint/test!` both return."
  [driver {:keys [seed traces cmd dir]}]
  (let [total (count traces)]
    (loop [i 0, used {}, steps 0]
      (if (= i total)
        {:ok? true :seed seed :traces total :steps steps :cmd cmd :dir dir
         :coverage (coverage driver used) :failure nil}
        (let [{:keys [name json]} (nth traces i)
              r                   (replay-json driver json)
              used'               (merge-with + used (get-in r [:coverage :used]))
              steps'              (+ steps (:steps r))]
          (if (:ok? r)
            (recur (inc i) used' steps')
            {:ok?      false :seed seed :traces total :steps steps' :cmd cmd :dir dir
             :coverage (coverage driver used')
             :failure  (assoc (:failure r) :trace i :trace-name name :trace-json json)}))))))

(defn check
  "Generate traces with Quint and replay every one against the implementation.

  Takes a resolved driver and an options map merged over it — `:traces`,
  `:max-steps`, `:max-samples`, `:seed`. Returns

    {:ok? false :seed 42 :traces 50 :steps 231 :cmd [\"quint\" ...]
     :coverage {:used {...} :unused #{...}}
     :failure  {... :trace 7 :trace-name \"run_7.itf.json\" :trace-json \"...\"}}

  Stops at the first trace that diverges. The failing trace's JSON is carried
  in the result rather than a path, because the temporary directory Quint wrote
  it to is deleted before returning.

  Throws whatever `quint/run!` and `replay/run-trace` throw: generation and
  setup problems are exceptions, divergence is this map."
  [driver opts]
  (replay-all driver (quint/run! (merge driver opts))))

(defn check-run
  "Replay one scripted Quint `run` against the implementation.

  Takes a resolved driver and an options map merged over it; `:test` names a
  `run` in the spec and is required. Returns what `check` returns, with
  `:traces` 1.

  `quint test` emits no `mbt::` variables, so the spec must record the action
  it took in an ordinary variable and the driver must say where with
  `:action-path` — and `:nondet-path` too, for actions that take picks.
  Without them the trace has no action to dispatch and replay throws
  `:unknown-action`.

  A scripted run is a scenario a human wrote down: use it for the case that
  must keep working, and `check` for the cases nobody thought of."
  [driver opts]
  (replay-all driver (quint/test! (merge driver opts))))

(defn verify
  "Check an invariant with Apalache, and replay the counterexample if there is
  one.

  Takes a resolved driver and an options map merged over it; `:invariant` names
  a `val` in the spec and is required. `:max-steps` bounds the search. Returns
  what `check` returns, plus `:invariant`:

    {:ok? false :traces 1 :steps 2 :cmd [...] :dir \"...\"
     :invariant {:name \"underFifty\" :holds? false
                 :trace-name \"verify.itf.json\" :trace-json \"...\"}
     :coverage {...}
     :failure  nil}

  A violated invariant is `:ok? false` whether or not the implementation
  agrees with the counterexample, because those are two different facts and
  both are reported. `:invariant` says the spec's own property does not hold.
  `:failure` says the implementation diverged from the counterexample, and is
  nil when it did not — which means the implementation reproduces the spec's
  bug faithfully. That is a real answer, not a pass, and it points at the spec.

  A holding invariant returns `:ok? true` with `:traces` 0 and no trace to
  replay, since `quint verify` writes no file in that case.

  Counterexamples carry no `mbt::` variables, so the driver needs the same
  `:action-path` a scripted run needs; without it replay throws
  `:unknown-action`.

  Throws whatever `quint/verify!` and `replay/run-trace` throw."
  [driver opts]
  (let [o (merge driver opts)
        {:keys [holds? cmd dir traces]} (quint/verify! o)
        base {:seed nil :cmd cmd :dir dir}]
    (if holds?
      (assoc base :ok? true :traces 0 :steps 0
             :invariant {:name (:invariant o) :holds? true}
             :coverage (coverage driver {})
             :failure nil)
      (let [{:keys [name json]} (first traces)
            r (replay-json driver json)]
        (assoc base
               :ok?       false
               :traces    1
               :steps     (:steps r)
               :invariant {:name (:invariant o) :holds? false
                           :trace-name name :trace-json json}
               :coverage  (coverage driver (get-in r [:coverage :used]))
               :failure   (some-> (:failure r)
                                  (assoc :trace 0 :trace-name name
                                         :trace-json json)))))))

(defn replay-file
  "Replay one committed ITF file. Needs no Quint installed — this is what makes
  a recorded failure a deterministic regression test. Returns a
  `replay/run-trace` result."
  [driver path]
  (replay-json driver (slurp path)))
