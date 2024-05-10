#!/bin/bash

: "${CHAIN_ID:=31338}"

CMD="/root/.foundry/bin/anvil --block-time 1 --host 0.0.0.0 --port 8545 --dump-state /data/anvil_state.json --chain-id $CHAIN_ID"

# Loading chain state on startup if exists
if [ -f "/data/anvil_state.json" ]; then
    CMD="$CMD --load-state /data/anvil_state.json"
fi

exec $CMD
