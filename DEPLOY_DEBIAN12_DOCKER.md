### HAL1000 Deployment (Debian 12 with Docker)

1) Install Docker Engine + Compose plugin

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian \
  $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
```

2) Get the code onto the server

```bash
git clone https://your-repo.example.com/hal1000.git
cd hal1000
```

Or copy from your machine:

```bash
scp -r /path/to/hal1000 user@server:/opt/hal1000
cd /opt/hal1000
```

3) Configure environment

```bash
cp .env.example .env
# Edit .env and set HAL1000_LLM_API_KEY=your_key
```

4) Build and run

```bash
docker compose up -d --build
docker compose logs -f
```

5) Verify

```bash
curl http://localhost:8080
# Or visit http://<server-ip>:8080 in a browser
```

Notes
- Data persists in the `hal1000_data` volume at `/data/hal1000.db` inside the container.
- To update: `git pull && docker compose build --no-cache && docker compose up -d`.
- For port 80: change mapping to `"80:8080"` in `docker-compose.yml`.

