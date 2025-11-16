export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'scope-enum': [
      2,
      'always',
      ['core', 'platform', 'web', 'docker', 'ci', 'deps', 'config', 'helm'],
    ],
    'body-max-line-length': [2, 'always', 200],
    'subject-case': [2, 'always', 'lower-case'],
  },
};
