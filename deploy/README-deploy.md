# Deploy do SGPUR — Oracle Cloud "Always Free" (São Paulo)

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
