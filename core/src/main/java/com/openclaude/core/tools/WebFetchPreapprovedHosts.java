package com.openclaude.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WebFetchPreapprovedHosts {
    private static final Set<String> PREAPPROVED_HOSTS = Set.of(
            "platform.claude.com",
            "code.claude.com",
            "modelcontextprotocol.io",
            "github.com/anthropics",
            "agentskills.io",
            "docs.python.org",
            "en.cppreference.com",
            "docs.oracle.com",
            "learn.microsoft.com",
            "developer.mozilla.org",
            "go.dev",
            "pkg.go.dev",
            "www.php.net",
            "docs.swift.org",
            "kotlinlang.org",
            "ruby-doc.org",
            "doc.rust-lang.org",
            "www.typescriptlang.org",
            "react.dev",
            "angular.io",
            "vuejs.org",
            "nextjs.org",
            "expressjs.com",
            "nodejs.org",
            "bun.sh",
            "jquery.com",
            "getbootstrap.com",
            "tailwindcss.com",
            "d3js.org",
            "threejs.org",
            "redux.js.org",
            "webpack.js.org",
            "jestjs.io",
            "reactrouter.com",
            "docs.djangoproject.com",
            "flask.palletsprojects.com",
            "fastapi.tiangolo.com",
            "pandas.pydata.org",
            "numpy.org",
            "www.tensorflow.org",
            "pytorch.org",
            "scikit-learn.org",
            "matplotlib.org",
            "requests.readthedocs.io",
            "jupyter.org",
            "laravel.com",
            "symfony.com",
            "wordpress.org",
            "docs.spring.io",
            "hibernate.org",
            "tomcat.apache.org",
            "gradle.org",
            "maven.apache.org",
            "asp.net",
            "dotnet.microsoft.com",
            "nuget.org",
            "blazor.net",
            "reactnative.dev",
            "docs.flutter.dev",
            "developer.apple.com",
            "developer.android.com",
            "keras.io",
            "spark.apache.org",
            "huggingface.co",
            "www.kaggle.com",
            "www.mongodb.com",
            "redis.io",
            "www.postgresql.org",
            "dev.mysql.com",
            "www.sqlite.org",
            "graphql.org",
            "prisma.io",
            "docs.aws.amazon.com",
            "cloud.google.com",
            "kubernetes.io",
            "www.docker.com",
            "www.terraform.io",
            "www.ansible.com",
            "vercel.com/docs",
            "docs.netlify.com",
            "devcenter.heroku.com",
            "cypress.io",
            "selenium.dev",
            "docs.unity.com",
            "docs.unrealengine.com",
            "git-scm.com",
            "nginx.org",
            "httpd.apache.org"
    );
    private static final Set<String> HOSTNAME_ONLY;
    private static final Map<String, List<String>> PATH_PREFIXES;

    static {
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        LinkedHashMap<String, List<String>> pathPrefixes = new LinkedHashMap<>();
        for (String entry : PREAPPROVED_HOSTS) {
            int separator = entry.indexOf('/');
            if (separator < 0) {
                hosts.add(entry.toLowerCase(Locale.ROOT));
                continue;
            }
            String host = entry.substring(0, separator).toLowerCase(Locale.ROOT);
            String path = entry.substring(separator);
            pathPrefixes.computeIfAbsent(host, ignored -> new ArrayList<>()).add(path);
        }
        HOSTNAME_ONLY = Set.copyOf(hosts);
        PATH_PREFIXES = Map.copyOf(pathPrefixes);
    }

    private WebFetchPreapprovedHosts() {}

    static boolean isPreapprovedHost(String hostname, String pathname) {
        String normalizedHost = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
        String normalizedPath = pathname == null || pathname.isBlank() ? "/" : pathname;
        if (HOSTNAME_ONLY.contains(normalizedHost)) {
            return true;
        }
        List<String> prefixes = PATH_PREFIXES.get(normalizedHost);
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
