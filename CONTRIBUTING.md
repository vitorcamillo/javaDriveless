# Contribuindo para Java Driverless

Obrigado por considerar contribuir! Qualquer ajuda é bem-vinda.

## Como Contribuir

### Reportar Bugs

Abra uma [Issue](https://github.com/seu-usuario/javaDriveless/issues) com:
- Descrição clara do problema
- Passos para reproduzir
- Comportamento esperado vs atual
- Versão do Java e Chrome
- Código de exemplo (se possível)

### Sugerir Features

Abra uma [Issue](https://github.com/seu-usuario/javaDriveless/issues) descrevendo:
- Caso de uso
- Benefícios da feature
- Exemplo de como seria usada

### Pull Requests

1. Fork o repositório
2. Clone seu fork: `git clone https://github.com/vitorcamillo/javaDriveless.git`
3. Crie uma branch: `git checkout -b feature/minha-feature`
4. Faça suas mudanças
5. Teste localmente: `mvn clean test`
6. Commit: `git commit -m "Descrição clara da mudança"`
7. Push: `git push origin feature/minha-feature`
8. Abra um Pull Request

### Padrões de Código

- Use Java 21+
- Siga convenções Java padrão
- Adicione JavaDoc para métodos públicos
- Mantenha compatibilidade com a API existente
- Adicione testes para novas features

### Estrutura de Commits

```
tipo: descrição curta

Descrição detalhada (opcional)

Closes #issue-number (se aplicável)
```

Tipos:
- `feat`: Nova feature
- `fix`: Correção de bug
- `docs`: Documentação
- `test`: Testes
- `refactor`: Refatoração
- `perf`: Performance

### Testes

Antes de enviar PR:

```bash
mvn clean test
mvn compile
```

Certifique-se de que todos os testes passam.

### Documentação

Atualize o README.md se sua mudança:
- Adiciona nova feature
- Muda comportamento existente
- Adiciona novos requisitos

## Áreas que Precisam de Ajuda

- Testes adicionais
- Suporte a Firefox/Edge
- Otimizações de performance
- Documentação e exemplos
- Traduções do README

## Dúvidas?

Abra uma [Discussion](https://github.com/vitorcamillo/javaDriveless/discussions) ou comente em uma Issue existente.

---

Obrigado pela contribuição!

