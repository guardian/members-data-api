This setup allows you to run the app on your local machine, relying on reverse proxying via Nginx to simulate a call to theguardian.com domain. This is needed as the API uses cookies from this domain.

- Install Nginx: `brew install nginx` or `apt-get install nginx`

- Go to your Nginx folder (`cd /usr/local/etc/nginx` if installed from brew)

- Create a `sites-enabled` folder if not already present: `mkdir -p sites-enabled`

- Edit the file nginx.conf to make sure that it contains the `include sites-enabled/*;` stanza inside the

```
http {
    ...
}
```

block

- Copy the `doc/members-data-api.conf` file from this repo to `sites-enabled`

- Generate a self-signed certificate for this subdomain in your Nginx folder i.e. `/usr/local/etc/nginx`:

```bash
    openssl genrsa -out "members-data-api.key" 2048
    openssl req -new -key "members-data-api.key" -out "members-data-api.csr"
    openssl x509 -req -in "members-data-api.csr" -signkey "members-data-api.key" -out "members-data-api.crt"
```

- Reload Nginx: `nginx -s reload`

- Redirect traffic from your subdomain to your local machine by adding this line to `/etc/hosts`:

`127.0.0.1   members-data-api.theguardian.com`

- Launch the app by going back to your workspace and running

```
    $ sbt
    > project membership-attribute-service
    > devrun
```

- The application should be available from `https://members-data-api.theguardian.com` after you ignore the warning for the bogu certificate.
