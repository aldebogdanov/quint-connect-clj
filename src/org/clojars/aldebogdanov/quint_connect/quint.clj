(ns org.clojars.aldebogdanov.quint-connect.quint
  "Run the Quint CLI and collect the ITF files it writes. The only namespace
  that shells out."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def tested-version
  "The Quint the fixtures were recorded from and the behaviour in
  docs/notes/itf-format.md was verified against."
  "0.32.0")

(defn- fail [error msg data]
  (throw (ex-info msg (assoc data :quint/error error))))

(defn- exec
  "Run quint, returning {:exit :out :err}. Throws `:quint-not-found` when the
  binary is missing, which is a different problem from the binary failing."
  [dir args]
  (let [p (try (apply process/start {:dir (or dir ".")} "quint" args)
               (catch java.io.IOException e
                 (fail :quint-not-found
                       "quint is not on PATH; trace generation needs it (replay does not)"
                       {:cause (ex-message e)})))
        ;; Both streams are pipes, and a pipe that fills blocks the writer.
        ;; Draining them one after the other deadlocks whenever the second one
        ;; fills while we are still reading the first.
        err (future (slurp (process/stderr p)))
        out (slurp (process/stdout p))]
    {:exit @(process/exit-ref p) :out out :err @err}))

(defn version
  "The version of the `quint` on PATH, as a string. Throws `ex-info` with
  `:quint/error` `:quint-not-found` if it is not there, `:quint-failed` if it
  will not report a version."
  []
  (let [{:keys [exit out err]} (exec nil ["--version"])]
    (when-not (zero? exit)
      (fail :quint-failed "quint --version failed" {:exit exit :stderr err}))
    (str/trim out)))

(def ^:private checked-version
  (delay
   (let [v (version)]
     (when-not (= tested-version v)
       (.println System/err
                 (str "quint-connect: found quint " v ", developed against "
                      tested-version "; ITF decoding may differ")))
     v)))

(defn- temp-dir! []
  (str (Files/createTempDirectory "quint-connect-" (into-array FileAttribute []))))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (.delete ^java.io.File f)))

(defn- collect [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".itf.json"))
       (sort-by #(parse-long (or (re-find #"\d+" (.getName ^java.io.File %)) "0")))
       (mapv (fn [f] {:name (.getName ^java.io.File f) :json (slurp f)}))))

(defn- run-args
  "Quint runs in the spec's own directory and writes into `out-dir`, so sibling
  modules resolve and `#meta.source` stays a bare filename."
  [{:keys [spec main init-action step-action seed traces max-samples max-steps]} out-dir]
  (cond-> ["run" (.getName (io/file spec))
           "--mbt"
           (str "--seed=" seed)
           (str "--n-traces=" traces)
           (str "--max-steps=" max-steps)
           (str "--out-itf=" out-dir "/run_{seq}.itf.json")
           "--verbosity=0"]
    main        (conj (str "--main=" main))
    init-action (conj (str "--init=" init-action))
    step-action (conj (str "--step=" step-action))
    ;; --max-samples is attempts, --n-traces is traces written: different
    ;; knobs, but Quint defaults samples to 1 and rejects n-traces > samples.
    ;; So :traces is the floor, never the value — raise :max-samples to let
    ;; Quint discard attempts that violate a precondition.
    :always     (conj (str "--max-samples=" (max (or max-samples 0) traces)))))

(defn- test-args
  "`quint test` has no `--mbt` and no `--n-traces`: one scripted run yields one
  trace. `--match` is a regex, anchored here so `depositTest` cannot also
  select `depositTestTwo`."
  [{:keys [spec main test seed max-samples]} out-dir]
  (cond-> ["test" (.getName (io/file spec))
           (str "--out-itf=" out-dir "/test_{test}_{seq}.itf.json")
           "--verbosity=0"]
    main        (conj (str "--main=" main))
    test        (conj (str "--match=^" test "$"))
    seed        (conj (str "--seed=" seed))
    max-samples (conj (str "--max-samples=" max-samples))))

(defn- verify-args
  "`quint verify` takes the spec by absolute path, because unlike `run` and
  `test` it is invoked from the scratch directory rather than the spec's own.
  See `in-scratch!` for why."
  [{:keys [spec main invariant init-action step-action max-steps]} out-dir]
  (cond-> ["verify" (.getAbsolutePath (io/file spec))
           (str "--out-itf=" out-dir "/verify.itf.json")
           "--verbosity=0"]
    main        (conj (str "--main=" main))
    invariant   (conj (str "--invariant=" invariant))
    init-action (conj (str "--init=" init-action))
    step-action (conj (str "--step=" step-action))
    max-steps   (conj (str "--max-steps=" max-steps))))

(defn- reproducible
  "The command as a user could re-run it: the scratch directory quint wrote to
  is deleted before we return, so the ITF path is reduced to its basename."
  [args]
  (mapv #(if (str/starts-with? % "--out-itf=")
           (str "--out-itf=" (.getName (io/file (subs % (count "--out-itf=")))))
           %)
        (cons "quint" args)))

(defn- in-scratch!
  "Run quint with ITF going into a scratch directory that is deleted before
  returning, the files having been read back out of it first.

  `:run-in` says where quint itself runs. `:spec-dir` is the spec's own
  directory, which `run` and `test` want so that sibling modules resolve and
  `#meta.source` stays a bare filename. `:scratch` is the scratch directory,
  which `verify` needs: Apalache writes an `_apalache-out/` directory of logs
  into the working directory, and the user's spec directory is the wrong place
  for it. The spec is passed absolute in that case, and the logs are deleted
  along with everything else here. See docs/notes/itf-format.md."
  [{:keys [spec run-in]} args-fn]
  (let [dir      (temp-dir!)
        args     (args-fn dir)
        spec-dir (.getParent (.getAbsoluteFile (io/file spec)))]
    (try
      (let [{:keys [exit out err]} (exec (if (= :scratch run-in) dir spec-dir) args)]
        {:exit exit :out out :err err
         :cmd (reproducible args) :dir spec-dir :traces (collect dir)})
      (finally
        (delete-tree! dir)))))

(defn run!
  "Generate traces with `quint run --mbt`.

  Takes the driver map's Quint keys: `:spec` (required), `:main`,
  `:init-action`, `:step-action`, `:seed`, `:traces`, `:max-steps` and
  `:max-samples`. A missing `:seed` is generated so a failure is reproducible.
  `:max-samples` is attempts and `:traces` is traces written; Quint requires
  the former to be at least the latter, so `:traces` acts as its floor.

  Runs in a temporary directory, reads the ITF files back, and deletes the
  directory before returning

    {:seed 42 :dir \"/path/to/spec\" :cmd [\"quint\" ...]
     :traces [{:name \"run_0.itf.json\" :json \"...\"}]}

  `:cmd` is the reproduce line, not the literal argv: the scratch `--out-itf`
  path is rewritten to a relative one, since the directory is already gone.

  Throws `ex-info` with `:quint/error` `:quint-not-found`, `:quint-failed`
  (carrying Quint's own stderr verbatim) or `:no-traces`."
  [{:keys [spec] :as opts}]
  (when-not spec
    (fail :quint-failed "no :spec in the driver" {:opts opts}))
  @checked-version
  (let [seed (or (:seed opts) (rand-int Integer/MAX_VALUE))
        {:keys [exit out err cmd dir traces]}
        (in-scratch! {:spec spec :run-in :spec-dir}
                     #(run-args (merge {:traces 10 :max-steps 20} opts {:seed seed}) %))
        info {:cmd cmd :seed seed :dir dir :stderr err :stdout out}]
    (when-not (zero? exit)
      (fail :quint-failed (str "quint exited " exit) (assoc info :exit exit)))
    (when (empty? traces)
      (fail :no-traces "quint wrote no traces; the spec may have no enabled actions" info))
    {:seed seed :dir dir :cmd cmd :traces traces}))

(defn test!
  "Run one scripted Quint `run` through `quint test` and read its trace back.

  Takes `:spec` and `:test` (both required), plus the optional `:main`,
  `:seed` and `:max-samples`. `:test` is the name of a `run` in the spec and is
  matched exactly. Returns the same shape as `run!`

    {:seed 42 :dir \"/path/to/spec\" :cmd [\"quint\" ...]
     :traces [{:name \"test_depositTest_0.itf.json\" :json \"...\"}]}

  Note that `quint test` emits no `mbt::` variables at all, so the trace can
  only drive an implementation if the spec records the action itself and the
  driver says where with `:action-path` — see `itf/itf->trace`.

  Throws `ex-info` with `:quint/error` `:quint-not-found`, `:test-failed` when
  the spec's own `.expect` did not hold (a problem in the spec, not in the
  implementation), `:quint-failed` for anything else non-zero, and `:no-traces`
  when the name matched nothing — which Quint reports by exiting 0 and writing
  no file."
  [{:keys [spec test] :as opts}]
  (when-not spec
    (fail :quint-failed "no :spec in the driver" {:opts opts}))
  (when-not test
    (fail :quint-failed "no :test to run; name a `run` from the spec" {:opts opts}))
  @checked-version
  (let [seed (:seed opts)
        {:keys [exit out err cmd dir traces]}
        (in-scratch! {:spec spec :run-in :spec-dir} #(test-args opts %))
        info {:cmd cmd :seed seed :dir dir :test test :stderr err :stdout out}]
    (when-not (zero? exit)
      (if (str/includes? (str out err) "Tests failed")
        (fail :test-failed
              (str "the spec's own test " (pr-str test) " failed its expectation;"
                   " that is a bug in the spec, before any implementation is involved")
              (assoc info :exit exit))
        (fail :quint-failed (str "quint exited " exit) (assoc info :exit exit))))
    (when (empty? traces)
      (fail :no-traces
            (str "no test named " (pr-str test) " in the spec")
            info))
    {:seed seed :dir dir :cmd cmd :traces traces}))

(defn verify!
  "Check an invariant with `quint verify`, which runs Apalache.

  Takes `:spec` and `:invariant` (both required), plus the optional `:main`,
  `:init-action`, `:step-action` and `:max-steps`. Returns

    {:holds? true  :cmd [\"quint\" ...] :dir \"/path/to/spec\" :traces []}
    {:holds? false :cmd [\"quint\" ...] :dir \"/path/to/spec\"
     :traces [{:name \"verify.itf.json\" :json \"...\"}]}

  The outcome is not in the exit code. Holding exits 0; a counterexample, an
  unknown invariant name, a spec that does not typecheck and a missing file all
  exit 1. What separates them is whether a trace was written, which is what
  this branches on — recorded in docs/notes/itf-format.md §`quint verify` and
  reproducible with dev/probes/verify_probe.sh.

  An invariant that holds writes no trace, so an empty result is the pass here
  and not the `:no-traces` error that `run!` and `test!` raise.

  Runs in a scratch directory rather than the spec's own, because Apalache
  writes `_apalache-out/` into the working directory; those logs are deleted
  with the scratch directory. Apalache is downloaded on first use and a run can
  take minutes.

  Throws `ex-info` with `:quint/error` `:quint-not-found`, or `:quint-failed`
  when quint exited non-zero without writing a counterexample — carrying
  Quint's own stderr, which is where the reason is."
  [{:keys [spec invariant] :as opts}]
  (when-not spec
    (fail :quint-failed "no :spec in the driver" {:opts opts}))
  (when-not invariant
    (fail :quint-failed "no :invariant to check; name a val from the spec"
          {:opts opts}))
  @checked-version
  (let [{:keys [exit out err cmd dir traces]}
        (in-scratch! {:spec spec :run-in :scratch} #(verify-args opts %))]
    (cond
      (zero? exit) {:holds? true  :cmd cmd :dir dir :traces []}
      (seq traces) {:holds? false :cmd cmd :dir dir :traces traces}
      :else        (fail :quint-failed
                         (str "quint verify exited " exit " and wrote no"
                              " counterexample, so the invariant was never"
                              " checked; the spec or the invariant name is the"
                              " likely cause")
                         {:cmd cmd :dir dir :invariant invariant :exit exit
                          :stderr err :stdout out}))))
