# Política de privacidade do app Android "E-Book Reader" (SomeCatCode)

Última atualização: 12 de outubro de 2026

## 1. Controlador

Felix Kurth
E-mail: it@wasmitleder.de

## 2. Resumo

O app lê e-books de **pastas no seu dispositivo que você escolhe** e, opcionalmente, de um **servidor Nextcloud que você mesmo informa**. O desenvolvedor não opera nenhum servidor para o app e **não recebe nenhum dado** de você ou do seu dispositivo. O app **não contém anúncios, rastreamento, análises nem relatórios de falhas**.

## 3. Dados que o app processa

Para funcionar, o app processa no seu dispositivo:

- **Credenciais:** o endereço do seu servidor Nextcloud, seu nome de usuário e uma senha de app. Seu servidor cria a senha de app quando você faz login pelo navegador (Nextcloud Login Flow). Ela é armazenada criptografada com o Android Keystore. O app nunca vê sua senha real do Nextcloud.
- **Dados da biblioteca:** títulos, autores, séries, gêneros, etiquetas, capas e outros detalhes dos seus livros, além das suas estantes.
- **Arquivos de livros** que você baixa para leitura offline, armazenados no armazenamento privado do app.
- **Pastas locais:** quando você adiciona uma pasta como biblioteca, o app lê os arquivos de livros dentro dela (pelo seletor de pastas do Android, somente essa pasta). Títulos, capas e outros detalhes, a posição de leitura e os destaques dessa biblioteca ficam somente no dispositivo. Somente quando você copia ou move explicitamente um livro para uma conta Nextcloud, o arquivo, a posição de leitura e os destaques dele são enviados a esse servidor.
- **Dados de leitura:** posição de leitura, status de leitura, avaliações, destaques e notas, e um nome de dispositivo escolhido por você (padrão: o modelo do dispositivo), que é enviado com a posição de leitura para que você veja em outros dispositivos onde parou.
- **Configurações do app**, como tema e configurações do leitor. Elas ficam no dispositivo.

## 4. Para onde os dados são transferidos

Os dados são transferidos **somente entre o seu dispositivo e o servidor Nextcloud que você informa no app**, sempre criptografados via HTTPS. O operador desse servidor é responsável pelo processamento nele: você, se você mesmo opera o servidor, ou, caso contrário, seu provedor de nuvem. Aplica-se a política de privacidade dele.

Os dados nunca são compartilhados com o desenvolvedor nem com qualquer outro terceiro.

Os links dentro dos livros abrem no navegador do seu dispositivo somente após a sua confirmação. Lá se aplicam as políticas de privacidade dos sites que você visita.

## 5. Permissões

- **Internet e estado da rede:** conexão com o seu servidor; downloads somente via Wi-Fi, se você escolher.
- **Notificações:** progresso e conclusão dos downloads. Você pode desativá-las a qualquer momento nas configurações do Android.
- **Serviço em primeiro plano (sincronização de dados):** downloads grandes continuam de forma confiável enquanto o app está em segundo plano.

O app não acessa sua localização, seus contatos, sua câmera, seu microfone nem seus outros arquivos.

## 6. Retenção e exclusão

Todos os dados ficam no dispositivo até que você os exclua:

- **"Remover conta"** no app exclui todos os dados locais dessa conta (biblioteca, livros baixados e credenciais) e revoga a senha de app no seu servidor. No caso de uma pasta local, a biblioteca, a posição de leitura e os destaques são excluídos e o acesso à pasta é devolvido; os arquivos de livros na pasta são mantidos.
- **Desinstalar** o app exclui todos os dados locais.
- Os livros baixados podem ser excluídos a qualquer momento em "Downloads".

Você gerencia os dados no seu servidor Nextcloud, como posição de leitura, destaques e estantes, no próprio servidor.

## 7. Base legal

O processamento no dispositivo serve apenas para fornecer os recursos que você solicita (Art. 6(1)(b) do RGPD). O desenvolvedor não processa nenhum dado pessoal.

## 8. Seus direitos

De acordo com o RGPD, você tem direito de acesso, retificação, exclusão, limitação do processamento, portabilidade dos dados e oposição, além do direito de apresentar reclamação a uma autoridade de controle. Como o desenvolvedor não recebe nenhum dado seu, entre em contato com o operador do seu servidor sobre os dados armazenados lá. Para dúvidas sobre o app, use o endereço de e-mail acima.

## 9. Crianças

O app não se destina a crianças menores de 16 anos.

## 10. Alterações

Atualizamos esta política quando o app ou a situação legal muda. Vale a versão publicada aqui.
