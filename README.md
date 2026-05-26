# Robo BT_7274

Robôs desenvolvidos como parte da disciplina de Introdução à Computação do 1º Período de Análise e Desenvolvimento de Sistemas do IFSC - Campus São José.

**Orientação**: [Diego Medeiros](https://github.com/diegomedeiros-IFSC)

**Equipe**:
- [Felipe Rezende](https://github.com/feliperezn)
- [Gustavo Niehues](https://github.com/GustavoNiehues)
- [Lucas de Godoy Chicarelli](https://github.com/lucasgch)
- [Mauricio Telles Silva](https://github.com/MauricioTellesSilva)

## Como baixar e instalar o robocode

Acesse o repositório oficial e faça o download da útima versão: [https://robocode.sourceforge.io/](https://robocode.sourceforge.io/)

Instale o robocode:
```shell
java -jar robocode-1.10.1-setup.jar 
```

Entre na pasta e execute:

```shell
cd robocode
./robocode.sh
```

## Como contribuir

Clone o repositório

`git clone https://github.com/lucasgch/titan`

Crie uma branch com o seu nome ou o nome do seu robô

`git branch checkout -b nomedabranch`

Se você é um contribuidor deste repositório, faça o push das alterações para o repositório.

`git push --set-upstream origin lucas`

Se você não é um contribuir, mas enviar um robô ou uma contribuição, envie um Pull Request.

## 🚀 Funcionalidades e Sistemas do BT-7274

O BT-7274 opera sob uma arquitetura de IA híbrida, alternando dinamicamente entre táticas de combate baseadas na contagem de inimigos (Melee vs 1v1) e no nível de inteligência do adversário.

### 🛡️ Movimentação e Defesa
* **Wave Surfing Dedicado (1v1):** Sistema de esquiva que mapeia as probabilidades de tiro do inimigo em 47 ângulos ("bins"). Possui um buffer de memória isolado apenas para o 1v1, garantindo que os dados não sejam poluídos pelos tiros aleatórios do modo Melee.
* **Path Simulation com Inertia Control:** O robô não apenas decide para onde ir, mas simula a física exata da engine do Robocode (aceleração de `+1.0` e frenagem de `-2.0`) tick-a-tick para prever se conseguirá desviar da bala a tempo.
* **Multi-Wave Pathing:** Durante a simulação de fuga de uma bala, o BT-7274 calcula se a sua rota de colisão cruzará com outras ondas secundárias no meio do caminho, escolhendo rotas que evitem "fogo cruzado".
* **Minimum Risk Movement (MRM):** Cérebro de navegação macro para o modo Melee. Avalia até 150 pontos no mapa aplicando forças gravitacionais (foge de cantos, paredes, centro da arena e múltiplos inimigos).
* **Modo Predador (Anti-basic Override):** Se o robô detecta que o adversário no 1v1 é um robô básico (não-surfer), ele desliga o processamento do Wave Surfing e injeta um bônus agressivo de atração de 250% no MRM, caçando o inimigo implacavelmente.
* **Passive Bullet Shadowing:** Em batalhas Melee, o robô é capaz de usar o corpo de outros inimigos como escudo, zerando o risco de áreas do mapa onde um inimigo "A" atiraria, mas a bala bateria no inimigo "B" antes.
* **Movimento Caótico (Jitter):** Introduz uma micro-tremulação baseada em ondas senoidais para quebrar o travamento de robôs que usam miras de predição linear.

### 🎯 Mira e Armamento (Virtual Guns)
O robô simula simultaneamente 9 armas diferentes em sua memória, disparando apenas a que tem a maior taxa de acerto contra o alvo atual.
1.  **GunWave GF:** *Guess Factor Targeting* clássico baseado em Visit Count. Letal contra alvos que tentam desviar.
2.  **KNN Pesado (Machine Learning):** *K-Nearest Neighbors*. Lê até 30.000 estados passados do inimigo (Aceleração, Velocidade Lateral, Curvas) e compara com o momento atual para prever o tiro.
3.  **ARMA (AutoRegressive Moving Average):** Estatística de Séries Temporais para prever posições baseadas em médias móveis.
4.  **ARIMA:** Evolução do ARMA que utiliza derivadas e diferenças para calcular movimentos não-estacionários do oponente.
5.  **Miras Auxiliares (Linear, Circular, Head-On e Médias):** Armas de *fallback* extremamente eficientes contra robôs básicos.
* **Acoplamento Wave-to-Wave:** Sincroniza o Surfing de defesa com o GunWave de ataque automaticamente em combates de Elite no 1v1.
* **Júri Dinâmico e Trava de Armas (Locks):** Avalia e pune armas ineficientes, travando a melhor. Contra bots básicos, bloqueia o uso de processamento do KNN/GunWave e usa apenas miras matemáticas exatas.

### 🧠 Inteligência e Profiling (Classificação de Inimigos)
* **Classificação Dinâmica:** Analisa perpendicularidade e taxas de reversão lateral (frenagens bruscas) para rotular inimigos em tempo real como: `Básico` (Clinger/Linear) ou `Avançado` (Surfer).
* **Sistema de Nêmesis:** Possui uma memória de longo prazo (entre rounds) que lembra quais robôs o derrotaram mais de 5 vezes seguidas. Caso enfrente o Nêmesis, quebra o próprio padrão de tiro e alterna a arma de forma forçada.
* **Detecção de Tiros por Queda de Energia:** Identifica o exato momento que um inimigo atirou ao ler quedas repentinas de 0.1 a 3.0 na barra de energia adversária.

### 🖥️ UX e Telemetria (HUD e Logs)
* **Pathing Visual:** Desenha diretamente na arena (usando `onPaint`) a linha exata da curva de fuga calculada pelo Wave Surfing e o destino projetado pelo MRM.
* **Painel Tático em Tela:** Exibe em tempo real:
    * Arma atual ativa e motivo da escolha (ex: Lock de Precisão, Lock de Nêmesis).
    * Hit Rate Global e Derrotas Seguidas.
    * Estado de Confiança do Motor (Percentual de controle do Wave Surfing vs MRM).
    * Precisão teórica ao vivo das 9 Virtual Guns.
* **Relatório Tático de Fim de Round:** Imprime no console do Java um log massivo detalhando o tipo de cada oponente detectado, a eficácia das armas e qual sistema defensivo controlou o robô.
* **Tributo Protocolo 3:** Em caso de vitória no round, exibe as clássicas últimas mensagens do Titã original e performa uma dança de vitória com o radar.
