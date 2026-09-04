import { existsSync, realpathSync } from 'node:fs';
import path from 'node:path';

export type AppPaths = {
  packaged: boolean;
  installRoot: string;
  nodeModules: string;
  rendererIndex: string;
  hostJar: string;
  javaExecutable: string;
  builtinSkills: string;
  providerCatalog: string;
  releaseManifest: string;
  appHome: string;
};

export type AppPathInputs = {
  packaged: boolean;
  appPath: string;
  resourcesPath: string;
  moduleDirectory: string;
  appHome: string;
  smokeMode: boolean;
  environment: NodeJS.ProcessEnv;
};

export function resolveAppPaths(input: AppPathInputs): AppPaths {
  if (input.packaged) {
    const resources = requiredRealPath(input.resourcesPath, 'INSTALL_RESOURCE_MISSING');
    const rendererIndex=containedPath(input.appPath, path.join(input.appPath, 'desktop-ui', 'dist', 'index.html'));
    if(!existsSync(rendererIndex))throw new Error('INSTALL_RESOURCE_MISSING');
    return {
      packaged: true,
      installRoot: resources,
      nodeModules: containedPath(input.appPath, path.join(input.appPath, 'desktop-shell', 'dist', 'runtime')),
      rendererIndex,
      hostJar: containedRealPath(resources, path.join(resources, 'agent-host.jar')),
      javaExecutable: containedRealPath(resources, path.join(resources, 'jre', 'bin', 'javaw.exe')),
      builtinSkills: containedRealPath(resources, path.join(resources, 'skills')),
      providerCatalog: containedRealPath(resources, path.join(resources, 'provider-catalog.json')),
      releaseManifest: containedRealPath(resources, path.join(resources, 'release-manifest.json')),
      appHome: path.resolve(input.appHome)
    };
  }

  const allowOverrides = input.smokeMode || !input.packaged;
  const javaOverride = allowOverrides ? input.environment.AGENT_DESKTOP_JAVA : undefined;
  const jarOverride = allowOverrides ? input.environment.AGENT_DESKTOP_HOST_JAR : undefined;
  const skillsOverride = allowOverrides ? input.environment.AGENT_DESKTOP_BUILTIN_SKILLS : undefined;
  const repositoryRoot = path.resolve(input.moduleDirectory, '..', '..');
  return {
    packaged: false,
    installRoot: path.join(repositoryRoot, 'packaging'),
    nodeModules: path.join(repositoryRoot, 'desktop-shell', 'dist', 'runtime'),
    rendererIndex: path.join(repositoryRoot, 'desktop-ui', 'dist', 'index.html'),
    hostJar: jarOverride ? path.resolve(jarOverride) : path.join(repositoryRoot, 'agent-host', 'target', 'agent-host-0.1.0-SNAPSHOT.jar'),
    javaExecutable: javaOverride ? path.resolve(javaOverride) : 'java',
    builtinSkills: skillsOverride ? path.resolve(skillsOverride) : path.join(repositoryRoot, 'skills'),
    providerCatalog: path.join(repositoryRoot, 'packaging', 'provider-catalog.json'),
    releaseManifest: path.join(repositoryRoot, 'packaging', 'stage', 'release-manifest.json'),
    appHome: path.resolve(input.appHome)
  };
}

function requiredRealPath(value: string, code: string): string {
  if (!existsSync(value)) throw new Error(code);
  return realpathSync.native(value);
}

function containedRealPath(root: string, candidate: string): string {
  return containedPath(root, requiredRealPath(candidate, 'INSTALL_RESOURCE_MISSING'));
}

function containedPath(root: string, candidate: string): string {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('INSTALL_RESOURCE_TAMPERED');
  return path.resolve(candidate);
}
