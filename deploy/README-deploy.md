# Deploy do SAUR — Oracle Cloud "Always Free" (São Paulo)

VM gratuita (não dorme), disco persistente e perto do banco Neon (sa-east-1).
A aplicação roda como serviço `systemd` e o Nginx faz o proxy na porta 80.

> Stack alvo: Ubuntu 22.04 · Java 21 (Temurin) · JAR `sgpur` · Nginx.

---

## 1) Criar a VM no Oracle Cloud

1. Crie a conta em https://www.oracle.com/cloud/free/ (pede cartão **só para
   verificação** — recursos "Always Free" não são cobrados).
2. **Compute → Instances → Create instance**:
   - **Name:** `sgpur`
   - **Region:** `Brazil East (São Paulo)` / `sa-saopaulo-1`
   - **Image:** Canonical **Ubuntu 22.04**
   - **Shape:** `VM.Standard.A1.Flex` (Ampere/ARM — Always Free), **1 OCPU / 6 GB**.
     *(Se não houver capacidade ARM, use `VM.Standard.E2.1.Micro`.)*
   - **SSH keys:** envie sua chave pública (ou gere e baixe a privada).
   - **Create.** Anote o **IP público**.

## 2) Abrir as portas (80 e 443)

**a) No console OCI** (rede virtual):
- Networking → sua **VCN** → **Security List** (ou NSG) → **Add Ingress Rules**:
  - Source `0.0.0.0/0`, IP Protocol `TCP`, Destination port `80`
  - (e outra para `443`, se for usar HTTPS)

**b) No sistema operacional** (a imagem Ubuntu da Oracle usa iptables):
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3) Instalar o Java 21 (Temurin)

```bash
ssh ubuntu@<IP_PUBLICO>

sudo apt update
sudo apt install -y wget gnupg apt-transport-https
sudo mkdir -p /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jre
java -version   # deve mostrar 21
```

## 4) Preparar usuário e diretórios

```bash
sudo useradd -r -m -d /opt/sgpur -s /usr/sbin/nologin sgpur || true
sudo mkdir -p /opt/sgpur/data/anexos
```

## 5) Enviar os arquivos (rode na SUA máquina, em `c:\Users\rafae\projetos\urgencia`)

Primeiro crie o `deploy/sgpur.env` a partir do `deploy/sgpur.env.example`,
preenchendo a senha do Neon e uma `SGPUR_ADMIN_PASSWORD` forte. Depois:

```bash
scp target/saur-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/tmp/sgpur.jar
scp deploy/sgpur.env                ubuntu@<IP>:/tmp/sgpur.env
scp deploy/sgpur.service            ubuntu@<IP>:/tmp/sgpur.service
```

No servidor:
```bash
sudo mv /tmp/sgpur.jar /opt/sgpur/sgpur.jar
sudo mv /tmp/sgpur.env /opt/sgpur/sgpur.env
sudo mv /tmp/sgpur.service /etc/systemd/system/sgpur.service
sudo chown -R sgpur:sgpur /opt/sgpur
sudo chmod 600 /opt/sgpur/sgpur.env
```

## 6) Subir o serviço

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now sgpur
sudo systemctl status sgpur --no-pager
curl -I http://localhost:8080/login    # espera HTTP 200
```
Logs: `journalctl -u sgpur -f`

## 7) Nginx (porta 80)

```bash
sudo apt install -y nginx
sudo cp /caminho/nginx-sgpur.conf /etc/nginx/sites-available/sgpur
# (ou crie o arquivo com o conteudo de deploy/nginx-sgpur.conf)
sudo ln -sf /etc/nginx/sites-available/sgpur /etc/nginx/sites-enabled/sgpur
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```
Acesse **http://<IP_PUBLICO>/** — login `admin` e a senha definida no env.

## 8) (Opcional) HTTPS com domínio

Com um domínio apontando para o IP:
```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d seu.dominio.gov.br
```

---

## Atualizar a aplicação (novas versões)

Na sua máquina: `mvn -DskipTests clean package`. Depois:
```bash
scp target/saur-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/tmp/sgpur.jar
ssh ubuntu@<IP> 'sudo mv /tmp/sgpur.jar /opt/sgpur/sgpur.jar && sudo chown sgpur:sgpur /opt/sgpur/sgpur.jar && sudo systemctl restart sgpur'
```

## Observações
- Os **anexos** ficam em `/opt/sgpur/data/anexos` (disco persistente da VM) — faça
  backup periódico junto com o banco Neon.
- O banco é o **Neon** (externo); a VM só roda o app. Não há perda de dados do
  banco ao recriar a VM.
- O `sgpur.env` contém segredos: está no `.gitignore` e tem permissão `600`.

## Backup dos anexos (cron)

Os anexos (documentos clínicos, ofícios, comprovantes SNT) só existem no disco
da VM — perder o disco é perder esses arquivos, mesmo com o banco intacto no
Neon. Use `deploy/backup-anexos.sh` para copiá-los periodicamente:

```bash
sudo cp /caminho/backup-anexos.sh /opt/sgpur/backup-anexos.sh
sudo chown sgpur:sgpur /opt/sgpur/backup-anexos.sh
sudo chmod 700 /opt/sgpur/backup-anexos.sh
```

Teste manualmente antes de agendar (local, ex. outro disco/ponto de montagem
ou volume de object storage montado na VM):
```bash
sudo -u sgpur BACKUP_DEST=/mnt/backup/sgpur /opt/sgpur/backup-anexos.sh
```
Ou para um destino remoto via SSH (rsync incremental, sem tar):
```bash
sudo -u sgpur BACKUP_DEST=usuario@host:/backup/sgpur /opt/sgpur/backup-anexos.sh
```

Agende via `crontab -u sgpur -e` (backup diário às 3h, log em `backup.log`):
```
0 3 * * * BACKUP_DEST=/mnt/backup/sgpur /opt/sgpur/backup-anexos.sh >> /opt/sgpur/backup.log 2>&1
```

Ajuste `BACKUP_DEST` (obrigatório) e opcionalmente `ANEXOS_DIR` e
`RETENCAO_DIAS` (dias de retenção de backups locais em `.tar.gz`; default 14).
Ver comentários no topo do script para detalhes.

> **Lembrete (ação manual do usuário, fora do escopo deste script):**
> confirme no **console do Neon** se o projeto/plano usado tem **backup
> automático / PITR (point-in-time recovery)** habilitado para o banco. Este
> script cobre só os anexos em disco — o banco propriamente dito depende da
> política de backup contratada no Neon.

## Se a rede bloquear SSH (proxy corporativo)

Se `ssh ubuntu@<IP>` falhar de uma rede corporativa (proxy bloqueando a
porta 22), use o **Oracle Cloud Shell** (terminal no navegador, no console
da OCI, ícone `>_` no topo) — ele roda na rede da própria Oracle, sem passar
pelo proxy. A chave privada (arquivo `ssh-key-AAAA-MM-DD.key`, baixado na
criação da VM) precisa estar no Cloud Shell:
```bash
find ~ -maxdepth 2 -iname "*.key" 2>/dev/null   # confere se a chave ja esta la
chmod 600 ~/ssh-key-AAAA-MM-DD.key
ssh -i ~/ssh-key-AAAA-MM-DD.key ubuntu@<IP>
```
Pra enviar um JAR novo por esse caminho: baixa o jar da sua máquina/IDE,
sobe pro Cloud Shell (botão de upload), depois `scp -i ~/ssh-key-...key
saur-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/tmp/` e segue o passo "Atualizar a
aplicação" acima já dentro da sessão SSH.

## Troubleshooting: SMTP "Authentication failed" / "535 Bad Credentials"

Se o envio de e-mail falhar mesmo com host/porta/usuário certos:

1. **Confirme a senha realmente em uso pelo processo** (não confie só no
   arquivo — ele pode ter sido editado depois do último restart):
   ```bash
   sudo systemctl status sgpur | grep "Main PID"
   sudo cat /proc/<PID>/environ | tr '\0' '\n' | grep MAIL_PASS
   ```
2. Se precisar ver o diálogo SMTP exato (o que o Java realmente manda pro
   Gmail), ative debug temporariamente no `sgpur.env`:
   ```
   SPRING_MAIL_PROPERTIES_MAIL_DEBUG=true
   SPRING_MAIL_PROPERTIES_MAIL_DEBUG_AUTH=true
   ```
   Reinicia o serviço, tenta enviar um e-mail, e procura a linha `AUTH
   PLAIN <base64>` no `journalctl -u sgpur`. Decodifica com
   `echo '<base64>' | base64 -d` — o formato é
   `\0<usuario>\0<senha>`. Se a senha decodificada for diferente da senha
   de app que você gerou no Google, o arquivo `sgpur.env` está com um valor
   errado (já aconteceu: uma segunda senha de app foi digitada por engano
   no lugar da testada). **Remova as duas variáveis de debug depois de
   resolver** — não deixar debug ligado em produção.
3. Teste a credencial isolada, sem depender do Java, com
   `deploy/testar-smtp.py` (usa `getpass`, a senha nunca aparece na tela).
4. Já eliminados como causa em incidentes anteriores (não reabrir): espaço
   na senha de app, mecanismo `AUTH LOGIN` vs `PLAIN`, bloqueio de porta 587
   pela Oracle (só a porta 25 é bloqueada por padrão), variável
   `SPRING_MAIL_*` sobrescrevendo via env.
