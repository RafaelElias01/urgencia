#!/usr/bin/env bash
# Roda os testes do SAUR (Linux/sandbox), forcando o JDK 21.
# Uso:  ./test.sh                    -> roda todos os testes
#       ./test.sh ProcessoServiceTest -> roda so uma classe de teste
set -e

for jdk in /usr/local/sdkman/candidates/java/21*-ms /usr/lib/jvm/*21*; do
    if [ -x "$jdk/bin/java" ]; then
        export JAVA_HOME="$jdk"
        break
    fi
done
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "JDK 21 nao encontrado. Instale via 'sdk install java 21.0.10-ms' ou defina JAVA_HOME." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

if [ -n "$1" ]; then
    mvn -q -ntp -Dtest="$1" test
else
    mvn -q -ntp package
fi
