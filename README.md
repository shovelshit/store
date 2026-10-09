# Store

Source for the Store web UI (`pages/store/`). The canonical domain is
`https://store.ltools.asia/`.

The `tools` parent repository owns the Cloudflare Worker and deployment.
Store Worker handlers remain in `worker/src/store/` in that parent because
they depend on shared account/authentication modules. The parent publishes
this UI at its expected relative path and supplies its `/store/auth/*` and
`/store/api/*` endpoints. Changes to those contracts require coordinated
parent changes. This repository does not deploy independently.

Local check (Node.js required):

```sh
node --test pages/store/*.test.cjs
```

Run the parent Worker and deployment tests after integration.
