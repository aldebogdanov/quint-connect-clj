(ns release
  "Bump the version everywhere a release touches, and say what to do next.

  Publishing stays a human step, so this stops at the edits: it never commits,
  tags, pushes or deploys. See docs/decisions/0008-release.md.

  What it exists to prevent is the two mistakes that have actually happened —
  running `bb deploy` with build.clj still on the released version, and leaving
  the tag unpushed after everything else went out."
  (:require [clojure.string :as str]))

(def ^:private coordinate-files
  "Every file that names the released coordinate. build.clj is the version's
  home; these repeat it, and the examples pin it so a copied directory runs."
  ["README.md"
   "docs/getting-started.md"
   "examples/counter/deps.edn"
   "examples/lru/deps.edn"
   "examples/queue/deps.edn"
   "examples/tokens/deps.edn"])

(defn- die [& msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit 1))

(defn- current-version []
  (second (re-find #"\(def version \"([^\"]+)\"\)" (slurp "build.clj"))))

(defn- unreleased-body
  "What stands under `## [Unreleased]`, or nil when the section is a placeholder."
  [changelog]
  (let [body (second (re-find #"(?s)## \[Unreleased\]\n\n(.*?)\n## \[" changelog))]
    (when-not (or (str/blank? body) (str/starts-with? body "Nothing since"))
      body)))

(defn- edit! [path f]
  (let [before (slurp path), after (f before)]
    (spit path after)
    (not= before after)))

(defn -main [& [version]]
  (when-not (and version (re-matches #"\d+\.\d+\.\d+" version))
    (die "usage: bb release <version>, as in bb release 0.6.0"))
  (let [from (current-version)]
    (when (= from version)
      (die "build.clj is already on" version))
    (when-not (unreleased-body (slurp "CHANGELOG.md"))
      (die "CHANGELOG.md has nothing under [Unreleased]; write it before releasing"))

    (edit! "build.clj" #(str/replace % (str "(def version \"" from "\")")
                                     (str "(def version \"" version "\")")))
    (edit! "CHANGELOG.md"
           #(str/replace-first
             % "## [Unreleased]\n\n"
             (format "## [Unreleased]\n\nNothing since %s.\n\n## [%s] — %s\n\n"
                     version version (str (java.time.LocalDate/now)))))
    (doseq [f coordinate-files]
      (edit! f #(str/replace % from version)))

    (println (format "%s -> %s in build.clj, CHANGELOG.md and %d coordinate files.\n"
                     from version (count coordinate-files)))
    (println "Read the diff, then:\n")
    (println "  bb test && bb test:all && bb install")
    (println (format "  git commit -am 'release: %s' && git tag -a v%s -m 'v%s'" version version version))
    (println (format "  git push origin main && git push origin v%s" version))
    (println "  bb deploy      # reads CLOJARS_USERNAME and a deploy token in CLOJARS_PASSWORD")))
