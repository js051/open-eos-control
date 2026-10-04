"""Production Bridge app connected to the independent, loopback-only Bulb failure peer."""
from open_eos_bridge.app import create_app

from .ccapi_bulb_peer import BulbPeer

peer = BulbPeer()
app = create_app()


@app.get("/__test/peer", include_in_schema=False)
def peer_address() -> dict[str, str]:
    return {"origin": peer.origin}
