#!/usr/bin/env bash
# Sobe o SGPUR (Linux / macOS / Git Bash).
# Uso:  ./start.sh            -> perfil dev (H2)
#       ./start.sh prod       -> perfil prod (PostgreSQL/Neon via application-local.yml ou env vars)
set -e
PERFIL="${1:-dev}"

# --- Java 21: usa o JAVA_HOME atual se valido; senao tenta caminhos comuns ---
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for c in \
      "/c/Users/rafae/Tools/jdk-21.0.11+10" \
      "$HOME/Tools/jdk-21" \
      "/usr/lib/jvm/temurin-21-jdk-amd64" \
      "/usr/lib/jvm/java-21-openjdk" \
      "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# --- Maven (PATH ou caminho conhecido) ---
MVN="$(command -v mvn || true)"
if [ -z "$MVN" ] && [ -x "/c/Users/rafae/Tools/apache-maven-3.9.6/bin/mvn" ]; then
  MVN="/c/Users/rafae/Tools/apache-maven-3.9.6/bin/mvn"
fi
if [ -z "$MVN" ]; then
  echo "Maven nao encontrado. Instale o Maven ou ajuste o caminho em start.sh." >&2
  exit 1
fi

echo "==> Subindo SGPUR | perfil: $PERFIL | JAVA_HOME: ${JAVA_HOME:-(PATH)}"
exec "$MVN" -DskipTests -Dspring-boot.run.profiles="$PERFIL" spring-boot:run
