#!/usr/bin/env bash
# Backup do diretorio de anexos do SAUR (documentos clinicos, oficios,
# comprovantes SNT etc.) - roda na VM Oracle, pensado para agendamento via
# cron. NAO faz backup do banco: o banco e o Neon (externo), com backup/PITR
# geridos no console do Neon - confirme la se o plano usado tem retencao
# habilitada (ver nota no README-deploy.md).
#
# Uso:
#   BACKUP_DEST=/mnt/backup/sgpur ./backup-anexos.sh
#   BACKUP_DEST=usuario@host:/backup/sgpur ./backup-anexos.sh   (via rsync/ssh)
#
# Variaveis (todas com default sensato para a VM de producao):
#   ANEXOS_DIR    - diretorio de origem (default: /opt/sgpur/data/anexos)
#   BACKUP_DEST   - destino do backup (obrigatorio; local ou remoto via rsync)
#   RETENCAO_DIAS - dias para manter backups locais antigos (default: 14)
#
# Agendamento sugerido (crontab do usuario sgpur), backup diario as 3h:
#   0 3 * * * BACKUP_DEST=/mnt/backup/sgpur /opt/sgpur/backup-anexos.sh >> /opt/sgpur/backup.log 2>&1

set -euo pipefail

ANEXOS_DIR="${ANEXOS_DIR:-/opt/sgpur/data/anexos}"
RETENCAO_DIAS="${RETENCAO_DIAS:-14}"

if [ -z "${BACKUP_DEST:-}" ]; then
    echo "Erro: defina BACKUP_DEST (destino do backup, local ou remoto via rsync)." >&2
    echo "Exemplo: BACKUP_DEST=/mnt/backup/sgpur $0" >&2
    exit 1
fi

if [ ! -d "$ANEXOS_DIR" ]; then
    echo "Erro: diretorio de anexos nao encontrado: $ANEXOS_DIR" >&2
    exit 1
fi

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

if [[ "$BACKUP_DEST" == *:* ]]; then
    # Destino remoto (usuario@host:/caminho) - espelha via rsync+ssh, sem
    # empacotar em tar (mantem incrementalidade: so envia o que mudou).
    echo "==> Sincronizando $ANEXOS_DIR -> $BACKUP_DEST (rsync via ssh)"
    rsync -az --delete "$ANEXOS_DIR"/ "$BACKUP_DEST"/
else
    # Destino local (outro disco/ponto de montagem) - gera um tar.gz datado,
    # simples de restaurar e de auditar (cada execucao fica um arquivo).
    mkdir -p "$BACKUP_DEST"
    ARQUIVO="$BACKUP_DEST/anexos-$TIMESTAMP.tar.gz"
    echo "==> Empacotando $ANEXOS_DIR -> $ARQUIVO"
    tar -czf "$ARQUIVO" -C "$(dirname "$ANEXOS_DIR")" "$(basename "$ANEXOS_DIR")"

    echo "==> Removendo backups locais com mais de $RETENCAO_DIAS dias em $BACKUP_DEST"
    find "$BACKUP_DEST" -maxdepth 1 -name 'anexos-*.tar.gz' -mtime "+$RETENCAO_DIAS" -print -delete
fi

echo "==> Backup concluido: $TIMESTAMP"
