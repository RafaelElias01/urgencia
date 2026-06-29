#!/usr/bin/env bash
# Sobe o SGPUR (Linux / macOS / Git Bash).
# Uso:  ./start.sh            -> perfil dev (H2)
#       ./start.sh prod       -> perfil prod (PostgreSQL/Neon via application-local.yml ou env vars)
set -e
PERFIL="${1:-dev}"
case "$PERFIL" in
  dev|prod) ;;
  *) echo "Perfil invalido '$PERFIL'. Use: ./start.sh [dev|prod]" >&2; exit 1 ;;
esac

# --- Java 21: garante a versao 21 (ignora um JAVA_HOME de outra versao) ---
is_java21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21'; }

if ! { [ -n "${JAVA_HOME:-}" ] && is_java21 "$JAVA_HOME"; }; then
  for c in \
      "/c/Users/rafae/Tools/jdk-21.0.11+10" \
      "$HOME/Tools/jdk-21" \
      "/usr/local/sdkman/candidates/java/21.0.10-ms" \
      "$(ls -d /usr/local/sdkman/candidates/java/21.* 2>/dev/null | head -1)" \
      "/usr/lib/jvm/temurin-21-jdk-amd64" \
      "/usr/lib/jvm/java-21-openjdk" \
      "/usr/lib/jvm/java-21-openjdk-amd64" \
      "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"; do
    if is_java21 "$c"; then export JAVA_HOME="$c"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || ! is_java21 "$JAVA_HOME"; then
  echo "JDK 21 nao encontrado. Instale o Temurin 21 ou defina JAVA_HOME para um JDK 21." >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# --- Maven (PATH ou caminho conhecido) ---
MVN="$(command -v mvn || true)"
if [ -z "$MVN" ] && [ -x "/c/Users/rafae/Tools/apache-maven-3.9.6/bin/mvn" ]; then
  MVN="/c/Users/rafae/Tools/apache-maven-3.9.6/bin/mvn"
fi
if [ -z "$MVN" ]; then
  echo "Maven nao encontrado. Instale o Maven ou ajuste o caminho em start.sh." >&2
  exit 1
fi

# --- Porta: 3000 no Codespace, 8080 em outros ambientes ---
if [ -n "${CODESPACES:-}" ] || [ -n "${CODESPACE_NAME:-}" ]; then
  PORTA=2500
else
  PORTA=8080
fi

# --- Libera a porta antes de subir ---
PORTA_PID="$(lsof -ti:"$PORTA" 2>/dev/null || true)"
if [ -n "$PORTA_PID" ]; then
  echo "==> Liberando porta $PORTA (PID $PORTA_PID)..."
  kill -9 $PORTA_PID 2>/dev/null || true
  sleep 1
fi

# --- URL e abertura de navegador ---
if [ -n "${CODESPACE_NAME:-}" ]; then
  DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
  URL="https://${CODESPACE_NAME}-${PORTA}.${DOMAIN}"
else
  URL="http://localhost:$PORTA"
fi

# Abre o navegador em background assim que a porta responder
(
  until curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORTA/login" 2>/dev/null | grep -qE "^[23]"; do sleep 2; done
  echo "==> Abrindo $URL"
  if [ -n "${BROWSER:-}" ] && [ -x "$BROWSER" ]; then
    "$BROWSER" "$URL" 2>/dev/null || true
  elif command -v open &>/dev/null; then
    open "$URL"
  elif command -v xdg-open &>/dev/null && [ -n "${DISPLAY:-}" ]; then
    xdg-open "$URL"
  fi
) &

echo "==> Subindo SGPUR | perfil: $PERFIL | porta: $PORTA | url: $URL | JAVA_HOME: ${JAVA_HOME:-(PATH)}"
exec "$MVN" -DskipTests \
  -Dspring-boot.run.arguments="--spring.profiles.active=$PERFIL --server.port=$PORTA" \
  spring-boot:run
