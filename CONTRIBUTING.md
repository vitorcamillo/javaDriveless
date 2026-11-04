# Contribuindo

Issues e pull requests são bem-vindos. Mantenha as mudanças focadas, explique o
comportamento visível para o usuário e inclua um teste de regressão determinístico
para o comportamento alterado.

Antes de abrir um pull request:

```bash
mvn -B -ntp clean test
mvn -B -ntp package
```

Testes que usam um navegador real, um site externo ou uma rede são opt-in. Não
inclua cookies, credenciais, dados pessoais, diretórios de perfis, screenshots,
URLs privadas ou arquivos gerados em issues, fixtures, commits ou pull requests.
Use `example.com` e valores sintéticos.

Use prefixos convencionais de commit, como `feat:`, `fix:`, `test:` e `docs:`.
Atualize o README quando as APIs públicas ou os requisitos de execução mudarem.
