# Lock Visualizer 🎵

Animação de equalizador na tela de bloqueio do Android, no ritmo de qualquer música
(Spotify, YouTube Music, Deezer...). Você escolhe estilo e cores.

## Estilos
- **LED (blocos)**: colunas de quadradinhos com reflexo, igual ao GIF
- **Barras**: barras arredondadas com marcador de pico
- **Espelhado**: barras que crescem para cima e para baixo a partir do centro
- **Circular**: barras em volta de um círculo que gira devagar

## Cores
Arco-íris, Cor única (você escolhe o tom no controle deslizante), Fogo, Oceano e Neon.
Também dá para ajustar quantidade de barras, sensibilidade, relógio, tela sempre acesa
e se a animação gira para a horizontal junto com o celular (ligado por padrão).

## Como instalar

### Opção A: Android Studio
1. Instale o Android Studio (gratuito).
2. File > Open e escolha esta pasta.
3. Ligue o celular no USB com a "Depuração USB" ativa e clique em ▶ Run.

### Opção B: sem instalar nada (GitHub)
1. Crie um repositório no GitHub e envie esta pasta.
2. Aba **Actions** > "Gerar APK" roda sozinho (ou clique em "Run workflow").
3. Ao terminar, baixe o arquivo **LockVisualizer-apk**, extraia o `app-debug.apk`
   e instale no celular (permita "instalar apps desconhecidos").

## Primeiro uso
1. Abra o app e ative **"Mostrar na tela de bloqueio"**.
2. Aceite: microfone/áudio (é assim que o Android deixa ler o ritmo; nada é gravado),
   notificações e **"Sobrepor a outros apps"**.
3. Toque uma música e bloqueie o celular. Ao acender a tela, a animação aparece.
   - Toque duas vezes para sair. Desbloquear pela digital também fecha.
   - Fecha sozinha uns 6 segundos depois que a música para.

## Se não funcionar
- **Xiaomi / Redmi / POCO**: Configurações > Apps > Lock Visualizer > Outras permissões >
  ative "Mostrar na tela de bloqueio" e "Abrir janelas em segundo plano".
- **Samsung / Xiaomi / Motorola**: tire o app da otimização de bateria
  (Bateria > Sem restrições), senão o sistema pode encerrar o serviço.
- Se a prévia só mostra uma ondinha parada mesmo com música, o fabricante bloqueou
  a captura do áudio global (raro, mas acontece em alguns modelos).
