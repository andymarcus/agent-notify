#!/usr/bin/env node

import { main } from "../src/agent-notify.js";

await main(process.argv.slice(2));
