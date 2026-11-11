# Egress proxy for NetworkPolicy.allow(...). Configuration and the domain
# filter are mounted per run; this image only provides tinyproxy.
FROM alpine:3.22
RUN apk add --no-cache tinyproxy \
 && mkdir -p /run/tinyproxy && chown nobody:nobody /run/tinyproxy
USER nobody
EXPOSE 8888
CMD ["tinyproxy", "-d", "-c", "/etc/tinyproxy/tinyproxy.conf"]
