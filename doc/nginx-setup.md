This setup allows you to run the app on your local machine, relying on reverse proxying via Nginx to simulate a call to theguardian.com domain. This is needed as the API uses cookies from this domain.

1. Install Nginx: `brew install nginx` or `apt-get install nginx`

1. Go to your Nginx folder (`cd /usr/local/etc/nginx` if installed from brew)

1. Create a `sites-enabled` folder if not already present: `mkdir -p sites-enabled`

1. Edit the file nginx.conf to make sure that it contains the `include sites-enabled/*;` stanza inside the

```
http {
    ...
}
```

block

1. Copy the `doc/members-data-api-local.conf` file from this repo to `sites-enabled`

1. Generate a self-signed certificate for this subdomain:

```bash
    openssl genrsa -out "members-data-api-local.key" 2048
    openssl req -new -key "members-data-api-local.key" -out "members-data-api-local.csr"
    openssl x509 -req -in "members-data-api-local.csr" -signkey "members-data-api-local.key" -out "members-data-api-local.crt"
```

1. Reload Nginx: `nginx -s reload`

1. Redirect traffic from your subdomain to your local machine by adding this line to `/etc/hosts`:

`127.0.0.1   members-data-api-local.thegulocal.com`

1. Launch the app by going back to your workspace and running

```
    $ sbt
    > project membership-attribute-service
    > devrun
```

1. The application should be available from `https://members-data-api-local.theguardian.com` after you ignore the warning for the bogu certificate.
