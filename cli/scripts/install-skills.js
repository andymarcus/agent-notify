#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SKILL_NAME = "agent-notify";

const scriptPath = fileURLToPath(import.meta.url);
const packageRoot = path.resolve(path.dirname(scriptPath), "..");
const bundledSkillPath = path.join(packageRoot, "skills", SKILL_NAME);

export function skillInstallTargets({ homeDirectory = os.homedir(), environment = process.env } = {}) {
  const codexHome = environment.CODEX_HOME
    ? path.resolve(environment.CODEX_HOME)
    : path.join(homeDirectory, ".codex");

  return [
    { agent: "Codex", path: path.join(codexHome, "skills", SKILL_NAME) },
    { agent: "Claude", path: path.join(homeDirectory, ".claude", "skills", SKILL_NAME) },
  ];
}

export function installSkills({
  sourceDirectory = bundledSkillPath,
  homeDirectory = os.homedir(),
  environment = process.env,
  output = console.log,
} = {}) {
  if (!fs.existsSync(path.join(sourceDirectory, "SKILL.md"))) {
    throw new Error(`Bundled skill is missing from ${sourceDirectory}.`);
  }

  const targets = skillInstallTargets({ homeDirectory, environment });
  for (const target of targets) {
    fs.mkdirSync(target.path, { recursive: true });
    fs.cpSync(sourceDirectory, target.path, { recursive: true, force: true });
    output(`Installed ${SKILL_NAME} skill for ${target.agent}: ${target.path}`);
  }
  return targets;
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) {
  try {
    installSkills();
  } catch (error) {
    console.error(`agent-notify: could not install agent skills: ${error.message}`);
    process.exitCode = 1;
  }
}
