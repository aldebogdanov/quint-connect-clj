(ns uno.michelada.quint-connect.core
  "The public API: build a driver, then check an implementation against a spec."
  (:require [uno.michelada.quint-connect.itf :as itf]
            [uno.michelada.quint-connect.quint :as quint]
            [uno.michelada.quint-connect.registry :as registry]
            [uno.michelada.quint-connect.replay :as replay]))

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

(defn- replay-json [driver json]
  (replay/run-trace driver (itf/itf->trace (itf/json->itf json))))

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
  (let [{:keys [seed traces cmd dir]} (quint/run! (merge driver opts))
        total                          (count traces)]
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

(defn replay-file
  "Replay one committed ITF file. Needs no Quint installed — this is what makes
  a recorded failure a deterministic regression test. Returns a
  `replay/run-trace` result."
  [driver path]
  (replay-json driver (slurp path)))
