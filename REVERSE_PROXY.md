### Reverse Proxy (HTTPS) in front of HAL1000

HAL1000 listens on port 8080 in the container. Use a reverse proxy to serve on 80/443 with TLS.

Option A: Caddy (automatic HTTPS)
```yaml
# docker-compose.caddy.yml
services:
  caddy:
    image: caddy:2
    container_name: caddy
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - hal1000
volumes:
  caddy_data:
  caddy_config:
```

```txt
# Caddyfile
example.com {
  reverse_proxy hal1000:8080
}
```

Run:
```bash
docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d
```

Option B: Nginx
```nginx
# /etc/nginx/sites-available/hal1000.conf
server {
    listen 80;
    server_name example.com;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable site and reload:
```bash
sudo ln -s /etc/nginx/sites-available/hal1000.conf /etc/nginx/sites-enabled/hal1000.conf
sudo nginx -t && sudo systemctl reload nginx
```

TLS
- Caddy obtains certificates automatically for public domains.
- With Nginx, use certbot (`sudo snap install certbot --classic && sudo certbot --nginx`).

