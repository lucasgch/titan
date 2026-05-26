package titan;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;

import robocode.*;
import robocode.util.Utils;

/**
 * BT-7274 (Versão Elite 6.6 - TOTALMENTE COMENTADA E DOCUMENTADA)
 * Estratégia Híbrida MIRA EXTREMA + Smart Fallback + Wave Surfing
 * * MOTOR SHADOW INTEGRADO + PASSIVE BULLET SHADOW.
 * * DYNAMIC DOWNSCALING, KNN PESADO & GUNWAVE.
 * * FIX: O MRM na força máxima e o desligamento do Surfing exigem que o alvo seja BÁSICO.
 */
public class BT_7274 extends AdvancedRobot {
    
    // =========================================================
    // CONSTANTES GLOBAIS OTIMIZADAS
    // =========================================================
    // Quantidade de pontos gerados ao redor do robô para avaliar o Minimum Risk Movement (MRM)
    private final int QUANTIDADE_PONTOS_PREVISTOS = 150;
    // Margem de segurança para evitar que o robô bata ou fique preso nas paredes
    private final double MARGEM_PAREDE = 22.5; 
    private Random aleatorio = new Random();

    // =========================================================
    // VARIÁVEIS DE ESTADO DO ROBÔ (Isoladas por Instância)
    // =========================================================
    private double potenciaTiroCorrente = 3; // Força inicial do tiro
    private double direcaoLateral = 1;       // Direção de escape lateral (1 = direita, -1 = esquerda)
    private double velocidadeInimigoAnterior = 0; // Usado para calcular a aceleração do inimigo
    
    // Armazena os dados de todos os inimigos detectados pelo radar
    HashMap<String, Robo> listaInimigos = new HashMap<>();
    // Lista de balas inimigas simuladas (usado no Bullet Shadowing)
    List<TiroInimigo> tirosSuspeitos = new ArrayList<>();
    
    Robo meuRobo = new Robo(); // Instância que guarda as posições e estados do nosso próprio robô
    Robo alvo;                 // O inimigo atual que decidimos focar e atacar
    
    // --- SISTEMA DE TELEMETRIA (MÉTRICAS & VIRTUAL GUNS EXPANDIDAS PARA 9) ---
    private int totalTirosDisparados = 0;
    private int totalTirosAcertados = 0;
    private int turnosPulados = 0; // Conta travamentos (Skipped Turns) por processamento lento
    
    // Arrays para rastrear a performance de cada uma das 9 armas virtuais
    private int[] disparosReaisVG = new int[9];
    private int[] acertosReaisVG = new int[9];
    private int ultimaVGEscolhida = -1; // Índice da arma que foi disparada no último tick
    private int vgAnteriorLog = -2;     // Usado para evitar spam no console ao trocar de arma
    private HashMap<Bullet, Integer> rastreioBalasVG = new HashMap<>(); // Mapeia a bala disparada com a arma que a atirou
    
    // Nomes amigáveis das 9 armas para exibição no HUD e Console
    private final String[] NOMES_VG = {"Auxiliar", "ARMA", "ARIMA", "Rede Neural", "Dyn. Clustering", "Anti-Trem", "Média GF", "KNN Pesado", "GunWave GF"};
    
    // --- VARIÁVEIS DE PERSISTÊNCIA ENTRE ROUNDS (STATIC) ---
    // Variáveis estáticas sobrevivem de um round para o outro
    private static int roundsSemEscolha = 0; // Conta quantos rounds seguidos ficamos sem definir uma arma boa
    private static int armaTravada = -1;     // Trava uma arma específica se ela for comprovadamente a melhor
    private static int ultimoRoundAvaliacao = -1; 
    private boolean escolheuArmaNaturalmente = false; // Flag para saber se a arma foi escolhida por mérito ou forçada por segurança
    
    // --- MEMÓRIA DE DERROTAS SEGUIDAS PARA O SISTEMA NÊMESIS ---
    // Se um robô nos vence muitas vezes, forçamos o robô a ser puramente agressivo/defensivo contra ele
    private static HashMap<String, Integer> derrotasSeguidas = new HashMap<>();
    
    // --- MEMÓRIA VISUAL DO PATH SIMULATION DO SURFING ---
    // Guarda os pontos da rota de fuga calculada para desenhá-los na tela (linha vermelha)
    private List<Point2D.Double> caminhoSurfingVisualizado = new ArrayList<>();
    
    // Object Pool: Pré-alocação de 450 pontos na memória para evitar travamentos do Garbage Collector
    List<Point2D.Double> posicoesPossiveis = new ArrayList<>(450);
    int qtdPontosAtivos = 0;
    
    Point2D.Double pontoAlvo = new Point2D.Double(60, 60); // Destino atual de movimentação do robô
    Rectangle2D.Double campoBatalha = new Rectangle2D.Double(); // Dimensões da arena
    
    int tempoInativo = 30; // Timer para forçar a reavaliação de movimento
    boolean modoFuga = false; // Ativado quando a vida do robô fica crítica
    private Movimento_1VS1 movimento1VS1; // Fallback legad de movimento (caso o Surfing falhe)
    
    // --- VARIÁVEIS DO VISIT COUNT SURFING E SISTEMA MERITOCRÁTICO ---
    private double energiaInimigoAnterior_1v1 = 100;
    // Buffers que contam em quais "bins" (ângulos) o inimigo costuma nos acertar
    private int[] estatisticasSurfing = new int[47]; // Memória de acertos no modo Melee (batalha em grupo)
    private int[] estatisticasSurfing1v1 = new int[47]; // Memória limpa e isolada apenas para o modo 1 contra 1
    ArrayList<OndaInimiga> ondasInimigas = new ArrayList<>(); // Ondas que estão voando na nossa direção
    private boolean habilitarSurfing = true;
    
    // Sensor de Imobilidade: conta os ticks que o alvo está parado
    private int inimigoTicksParado_1v1 = 0;
    
    // Sistema Meritocrático: Pesos que definem quem controla o volante do robô (Surfing vs MRM)
    private double confiancaSurfing = 1.0;
    private double confiancaMRM = 1.0;
    double ultimoRiscoSurfingAvaliado = 0;
    double ultimoRiscoMRMAvaliado = 0;
    double riscoSurfingAlvoAtual = 0;
    double riscoMRMAlvoAtual = 0;

    // --- Constantes Estáticas do GuessFactor (Segmentação da Memória de Tiro) ---
    private static final int INDICES_DISTANCIA = 6;
    private static final int INDICES_VELOCIDADE = 6;
    private static final int BINS = 47; // Quantidade de fatias de ângulo possíveis para atirar
    private static final int BIN_CENTRAL = (BINS - 1) / 2; // O tiro direto no alvo é o bin 23
    private static final double ANGULO_ESCAPE_MAXIMO = 0.7; // Ângulo máximo teórico de fuga de um robô
    private static final double LARGURA_BIN = ANGULO_ESCAPE_MAXIMO / (double) BIN_CENTRAL; // Tamanho em radianos de cada fatia
    
    // Buffer Dinâmico Isolado: Tabela multidimensional que memoriza os hábitos de fuga do inimigo
    // Segmentado por: Distância, Velocidade, Última Velocidade e Bin do Acerto.
    private final int[][][][] buffersEstatisticos = new int[INDICES_DISTANCIA][INDICES_VELOCIDADE][INDICES_VELOCIDADE][BINS];
    
    // =========================================================
    // INICIALIZAÇÃO
    // =========================================================
    public BT_7274() {
        movimento1VS1 = new Movimento_1VS1(this);
        // Preenchendo a Object Pool no momento da criação do robô
        for (int i = 0; i < 450; i++) {
            posicoesPossiveis.add(new Point2D.Double());
        }
    }

    // =========================================================
    // HUD VISUAL NO ROBÔ (Método chamado automaticamente pelo Robocode)
    // =========================================================
    public void onPaint(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.drawString("== BT-7274 STATUS ==", 10, 15);
        
        // Exibição do estado da Arma Atual e dos Locks de Segurança
        if (ultimaVGEscolhida == -1) {
            g.drawString("Arma Ativa: Coletando Dados...", 10, 30);
        } else {
            String sufixo = (!escolheuArmaNaturalmente) ? " (Forçada/Alternando)" : "";
            double hitRateAtual = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 100.0;
            
            boolean forcarPorDerrotasHUD = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
            boolean ehAvancadoHUD = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
            
            if (getOthers() <= 1 && ehAvancadoHUD && !escolheuArmaNaturalmente && ultimaVGEscolhida == 8) sufixo = " (Lock: Surfing 1v1 Ativo)";
            else if (forcarPorDerrotasHUD && !escolheuArmaNaturalmente) sufixo = " (Lock Vingança: Alternando)";
            else if (totalTirosDisparados >= 15 && hitRateAtual < 10.0 && !escolheuArmaNaturalmente) sufixo = " (Lock: Precisão < 10% - Alternando)";
            else if (alvo != null && alvo.classificadoComoSurfer && !escolheuArmaNaturalmente) {
                if (alvo.agressividade > 2.0) sufixo = " (Anti-Surfer Agr - Alternando)";
                else sufixo = " (Anti-Surfer Padrão - Alternando)";
            }
            
            g.drawString("Arma Ativa: " + NOMES_VG[ultimaVGEscolhida] + sufixo, 10, 30);
        }
        
        // Renderiza a taxa de precisão global e derrotas passadas
        double hitRateExibicao = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 0.0;
        g.drawString("Hit Rate Global: " + String.format(Locale.US, "%.2f%%", hitRateExibicao), 10, 45);
        if (alvo != null && alvo.nome != null) {
            int mortes = derrotasSeguidas.getOrDefault(alvo.nome, 0);
            if (mortes > 0) g.drawString("Derrotas Seguidas: " + mortes, 10, 60);
        }

        // --- HUD DE TELEMETRIA DE MOVIMENTO ---
        g.setColor(new Color(255, 200, 0)); 
        g.drawString("== TELEMETRIA MOVIMENTO ==", 10, 85);
        g.drawString("Modo Atual: " + (modoFuga ? "FUGA (Crítico)" : "COMBATE (Ataque/Evasão)"), 10, 100);
        
        // Exibição dos pesos do motor dinâmico e status do Modo Predador
        String txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: %.2f | MRM: %.2f", confiancaSurfing, confiancaMRM);
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            txtPesos = "Confiança Motor -> Surf: 1.00 | MRM: 0.00 (OVERRIDE ANTI-SURFER)"; // Foco defensivo
        } else if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
            txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: 0.00 (OFF) | MRM: %.2f (BOOST AGRESSIVO)", (confiancaMRM * 2.5)); // Foco de perseguição
        } else if (getOthers() > 1) { 
            txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: %.2f (BOOST MELEE) | MRM: %.2f", (confiancaSurfing * 2.5), confiancaMRM);
        }
        g.drawString(txtPesos, 10, 115);
        
        // Indica qual array de memória o Surf está lendo
        boolean ehAvancadoMemoria = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        String bufferUsado = (getOthers() <= 1 && ehAvancadoMemoria) ? "1v1 Dedicado (c/ Inertia)" : "Melee Geral";
        g.drawString("Buffer Surfing: " + bufferUsado, 10, 130);
        g.drawString(String.format(Locale.US, "Risco do Destino -> Surf: %.2f | MRM: %.2f", riscoSurfingAlvoAtual, riscoMRMAlvoAtual), 10, 145);

        // --- HUD DE PRECISÃO DAS VTs ---
        g.setColor(new Color(100, 255, 100)); 
        g.drawString("== PRECISÃO DAS VTs USADAS ==", 10, 170);
        int yHUD = 185;
        for (int i = 0; i < 9; i++) {
            double vgRate = disparosReaisVG[i] > 0 ? ((double)acertosReaisVG[i] / disparosReaisVG[i]) * 100.0 : 0.0;
            g.drawString(String.format(Locale.US, "[%s] %.1f%% (%d/%d)", NOMES_VG[i], vgRate, acertosReaisVG[i], disparosReaisVG[i]), 10, yHUD);
            yHUD += 15;
        }

        // --- RENDERIZA O CAMINHO CALCULADO DO SURF (Linha Vermelha) ---
        if (caminhoSurfingVisualizado != null && !caminhoSurfingVisualizado.isEmpty()) {
            g.setColor(new Color(255, 50, 50, 180)); 
            for (int i = 0; i < caminhoSurfingVisualizado.size(); i++) {
                Point2D.Double pt = caminhoSurfingVisualizado.get(i);
                g.fillOval((int)pt.x - 2, (int)pt.y - 2, 4, 4);
                if (i > 0) {
                    Point2D.Double ptAnt = caminhoSurfingVisualizado.get(i - 1);
                    g.drawLine((int)ptAnt.x, (int)ptAnt.y, (int)pt.x, (int)pt.y);
                }
            }
            Point2D.Double ultimoPt = caminhoSurfingVisualizado.get(caminhoSurfingVisualizado.size() - 1);
            g.drawString("Path Surf", (int)ultimoPt.x + 10, (int)ultimoPt.y);
        }

        // --- RENDERIZA O DESTINO CALCULADO DO MRM (Círculo Ciano) ---
        if (pontoAlvo != null && pontoAlvo.x > 0 && pontoAlvo.y > 0) {
            g.setColor(new Color(0, 255, 255, 150)); 
            g.drawOval((int)pontoAlvo.x - 15, (int)pontoAlvo.y - 15, 30, 30);
            g.drawLine((int)meuRobo.x, (int)meuRobo.y, (int)pontoAlvo.x, (int)pontoAlvo.y);
            g.drawString("Destino (MRM)", (int)pontoAlvo.x + 20, (int)pontoAlvo.y);
        }
    }

    // =========================================================
    // CLASSES AUXILIARES (Estruturas de Dados Customizadas)
    // =========================================================
    
    // Modela informações sobre qualquer robô (Nós ou Inimigos)
    class Robo extends Point2D.Double {
        public long tempoVarredura; // Último tick que escaneamos ele
        public boolean vivo = true;
        public double energia;
        public String nome;
        public double anguloCanhaoRadianos;
        public double anguloAbsolutoRadianos;
        public double velocidade;
        public double direcao;
        public double ultimaDirecao;
        public double pontuacaoDisparo; // Define se ele é a maior prioridade de ataque no Melee
        public double distancia; 
        
        public double fatorAmeaca = 1.0; 
        public double energiaAnterior = 100;
        public double agressividade = 0.0; // Sobe quando ele atira em nós
        
        public double saldoPrecisao = 0.0; // Sobe se ele acerta nossos tiros
        
        public double fatorSurf = 0.0; // Mede se o robô tem comportamentos típicos de Wave Surfer
        public int reversoesLaterais = 0; // Quantas vezes ele inverteu a direção (frenadas bruscas)
        public double ultimaVelocidadeLateral = 0.0;
        public boolean classificadoComoSurfer = false; // Flag se é um robô de Elite
        
        public boolean classificadoComoBasico = false; // Flag se é um robô fraco/Linear
        public int ticksParado = 0; 
        
        public int ticksDesdeReversao = 0;
        
        public double pesoAprendizadoDinâmico = 1.0;
        
        // Históricos para as armas de Machine Learning e Séries Temporais (ARMA/ARIMA)
        public LinkedList<java.lang.Double> historicoVelocidade = new LinkedList<>();
        public LinkedList<java.lang.Double> historicoDeltaDirecao = new LinkedList<>();
        
        // Tabela de performance de cada arma virtual contra este inimigo específico
        public double[] acertosVirtualGuns = new double[9];
        
        // Memória para o algoritmo K-Nearest Neighbors (KNN Pesado)
        public List<double[]> historicoKNN_features = new ArrayList<>();
        public List<Integer> historicoKNN_bins = new ArrayList<>();
    }
    
    // Modela uma bala detectada (Tiro)
    class TiroInimigo {
        public Point2D.Double origem;
        public double velocidade;
        public double angulo;
        public long tempoDisparo;
    }
    
    // Funções matemáticas úteis para trigonometria e projeção vetorial
    public static class Utilitario {
        static double limitar(double valor, double min, double max) {
            return Math.max(min, Math.min(max, valor));
        }
        
        static double aleatorioEntre(double min, double max) {
            return min + Math.random() * (max - min);
        }
        
        // Encontra a coordenada X,Y baseada numa distância e ângulo
        static Point2D projetar(Point2D origem, double angulo, double distancia) {
            return new Point2D.Double(
                origem.getX() + Math.sin(angulo) * distancia,
                origem.getY() + Math.cos(angulo) * distancia
            );
        }
        
        // Encontra o ângulo entre dois pontos
        static double anguloAbsoluto(Point2D origem, Point2D alvo) {
            return Math.atan2(alvo.getX() - origem.getX(), alvo.getY() - origem.getY());
        }
        
        static int sinal(double v) {
            return v < 0 ? -1 : 1;
        }
    }

    // =========================================================
    // VISIT COUNT SURFING (SISTEMA DE DEFESA WAVE SURFING)
    // =========================================================
    
    // Representa uma "Onda" ou explosão detectada quando o inimigo atira
    class OndaInimiga {
        Point2D.Double origem;
        long tempoDisparo;
        double velocidadeBala;
        double anguloDireto; // Ângulo Head-On na hora do tiro
        double direcaoLateral;
        
        static final int BINS_SURF = 47;
        static final int BIN_CENTRO = 23;

        // Verifica se a onda virtual já nos alcançou no mapa
        public boolean checarAcerto(double posX, double posY, long tempoAtual) {
            double distanciaPercorrida = (tempoAtual - tempoDisparo) * velocidadeBala;
            if (distanciaPercorrida > Point2D.distance(origem.x, origem.y, posX, posY) - 18) {
                int bin = obterBin(posX, posY); // Pega qual foi a fatia do ângulo em que estávamos
                estatisticasSurfing[bin]++; // Anota no buffer geral que é perigoso ficar neste bin
                
                // Se o inimigo é avançado e estamos no 1v1, salva na memória isolada e limpa
                boolean ehAvancadoChecagem = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                if (getOthers() <= 1 && ehAvancadoChecagem) {
                    estatisticasSurfing1v1[bin]++;
                }
                return true; // A onda passou
            }
            return false;
        }

        // Converte coordenadas X,Y para um dos 47 "Bins" (índices de ângulo)
        public int obterBin(double alvoX, double alvoY) {
            double anguloDesejado = Math.atan2(alvoX - origem.x, alvoY - origem.y);
            double offset = Utils.normalRelativeAngle(anguloDesejado - anguloDireto);
            double maxEscapeAngle = Math.asin(8.0 / velocidadeBala);
            int bin = (int) Math.round((offset / (direcaoLateral * (maxEscapeAngle / BIN_CENTRO))) + BIN_CENTRO);
            return (int) Utilitario.limitar(bin, 0, BINS_SURF - 1);
        }
    }

    // Método mestre de movimentação defensiva
    public void executarSurfing() {
        if (ondasInimigas.isEmpty()) return;
        
        // Se estamos no 1v1 e sabemos que o inimigo atira de forma burra (não prevê movimento),
        // Desligamos o Wave Surfing, pois desviar das ondas seria contra-intuitivo e gastaria energia.
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
            return; 
        }

        // Define a nossa posição atual para a simulação
        meuRobo.x = getX();
        meuRobo.y = getY();
        meuRobo.direcao = getHeadingRadians();
        meuRobo.velocidade = getVelocity();

        OndaInimiga ondaMaisProxima = null;
        double menorDistancia = Double.MAX_VALUE;

        // Limpa ondas antigas e acha a onda que vai nos atingir primeiro
        for (int i = 0; i < ondasInimigas.size(); i++) {
            OndaInimiga onda = ondasInimigas.get(i);
            if (onda.checarAcerto(meuRobo.x, meuRobo.y, getTime())) {
                confiancaSurfing = Math.min(3.0, confiancaSurfing + 0.05);
                confiancaMRM = Math.min(3.0, confiancaMRM + 0.05);
                ondasInimigas.remove(i);
                i--;
                continue;
            }
            double distVoo = (getTime() - onda.tempoDisparo) * onda.velocidadeBala;
            double distRestante = onda.origem.distance(meuRobo) - distVoo;
            if (distRestante > 0 && distRestante < menorDistancia) {
                menorDistancia = distRestante;
                ondaMaisProxima = onda;
            }
        }

        // Atualiza a visualização do caminho simulado no HUD se houver uma onda a caminho
        if (ondaMaisProxima != null) {
            double riscoFrente = preverRiscoMovimento(ondaMaisProxima, 1);
            double riscoTras = preverRiscoMovimento(ondaMaisProxima, -1);
            double riscoParado = preverRiscoMovimento(ondaMaisProxima, 0);

            int direcaoSeguraVis = 1; // Default: pra frente
            if (riscoTras < riscoFrente && riscoTras <= riscoParado) direcaoSeguraVis = -1; // Pra trás é melhor
            else if (riscoParado < riscoFrente && riscoParado < riscoTras) direcaoSeguraVis = 0; // Ficar parado é mais seguro
            
            atualizarCaminhoVisual(ondaMaisProxima, direcaoSeguraVis);
        }

        boolean conflitoMotores = false; 
        if (conflitoMotores && ondaMaisProxima != null) {
            // Este bloco gerencia a resposta direta do Surfing caso seja ativado 
            // (Na versão atual o controle final é mixado pelo método 'avaliarPonto' do MRM)
            double riscoFrente = preverRiscoMovimento(ondaMaisProxima, 1);
            double riscoTras = preverRiscoMovimento(ondaMaisProxima, -1);
            double riscoParado = preverRiscoMovimento(ondaMaisProxima, 0);

            int direcaoSegura = 1;
            if (riscoTras < riscoFrente && riscoTras <= riscoParado) direcaoSegura = -1;
            else if (riscoParado < riscoFrente && riscoParado < riscoTras) direcaoSegura = 0;

            if (direcaoSegura != 0) {
                double tempoTentativa = 0;
                Point2D.Double destinoRobo = null;
                // Área segura dentro da arena
                Rectangle2D areaEvasao = new Rectangle2D.Double(MARGEM_PAREDE, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE * 2, campoBatalha.height - MARGEM_PAREDE * 2);

                // Wall-Smoothing: Procura um ângulo onde não vamos bater na parede
                while (!areaEvasao.contains(destinoRobo = (Point2D.Double) Utilitario.projetar(
                        ondaMaisProxima.origem, 
                        Utilitario.anguloAbsoluto(ondaMaisProxima.origem, meuRobo) + (Math.PI / 2 + tempoTentativa / 100.0) * direcaoSegura, 
                        ondaMaisProxima.origem.distance(meuRobo))) 
                        && tempoTentativa < 125) {
                    tempoTentativa++;
                }
                
                double anguloEvasao = Utilitario.anguloAbsoluto(meuRobo, destinoRobo) - getHeadingRadians();
                
                if (Math.cos(anguloEvasao) < 0) {
                    anguloEvasao += Math.PI;
                    direcaoSegura *= -1;
                }
                
                setMaxVelocity(8);
                setTurnRightRadians(Utils.normalRelativeAngle(anguloEvasao));
                setAhead(100 * direcaoSegura);
            } else {
                setMaxVelocity(0); // Fica parado para desviar (Surfing passivo)
            }
        }
    }

    // Calcula todas as posições futuras para popular a linha gráfica do HUD
    private void atualizarCaminhoVisual(OndaInimiga ondaPrimaria, int direcaoAcao) {
        caminhoSurfingVisualizado.clear();
        double posPrevistaX = meuRobo.x;
        double posPrevistaY = meuRobo.y;
        double velocidadeSimulada = getVelocity();
        double direcaoSimulada = getHeadingRadians();
        
        long tempoVoo = (long) ((ondaPrimaria.origem.distance(meuRobo) - ((getTime() - ondaPrimaria.tempoDisparo) * ondaPrimaria.velocidadeBala)) / ondaPrimaria.velocidadeBala);

        for (int i = 0; i < Math.max(1, tempoVoo); i++) {
            // Aplica Inércia (Não altera velocidade instantaneamente, obedece as regras do motor de física do Robocode)
            double velDesejada = direcaoAcao * 8.0;
            if (velocidadeSimulada < velDesejada) {
                velocidadeSimulada += (velocidadeSimulada < 0) ? 2.0 : 1.0;
                if (velocidadeSimulada > velDesejada) velocidadeSimulada = velDesejada;
            } else if (velocidadeSimulada > velDesejada) {
                velocidadeSimulada -= (velocidadeSimulada > 0) ? 2.0 : 1.0;
                if (velocidadeSimulada < velDesejada) velocidadeSimulada = velDesejada;
            }
            
            direcaoSimulada = Math.atan2(posPrevistaX - ondaPrimaria.origem.x, posPrevistaY - ondaPrimaria.origem.y) + (Math.PI/2) * (direcaoAcao == 0 ? 1 : direcaoAcao);
            
            posPrevistaX += Math.sin(direcaoSimulada) * velocidadeSimulada;
            posPrevistaY += Math.cos(direcaoSimulada) * velocidadeSimulada;

            posPrevistaX = Utilitario.limitar(posPrevistaX, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
            posPrevistaY = Utilitario.limitar(posPrevistaY, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);
            
            caminhoSurfingVisualizado.add(new Point2D.Double(posPrevistaX, posPrevistaY));
        }
    }

    // Path Simulator: Testa um caminho fantasma até o impacto da bala e vê quanto risco existe na área final
    private double preverRiscoMovimento(OndaInimiga ondaPrimaria, int direcaoAcao) {
        double posPrevistaX = meuRobo.x;
        double posPrevistaY = meuRobo.y;
        double velocidadeSimulada = getVelocity();
        double direcaoSimulada = getHeadingRadians();
        double riscoPath = 0; // Risco de ondas secundárias baterem em nós no trajeto
        
        long tempoVoo = (long) ((ondaPrimaria.origem.distance(meuRobo) - ((getTime() - ondaPrimaria.tempoDisparo) * ondaPrimaria.velocidadeBala)) / ondaPrimaria.velocidadeBala);
        long tempoSimulado = getTime();

        // Escolhe ler da memória isolada e limpa (1v1) ou da memória poluída (Melee) dependendo da fase da batalha
        boolean isAvancado = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        int[] bufferSurf = (getOthers() <= 1 && isAvancado) ? estatisticasSurfing1v1 : estatisticasSurfing;

        // Avança o fantasma tick-a-tick no futuro
        for (int i = 0; i < Math.max(1, tempoVoo); i++) {
            tempoSimulado++;
            
            // --- INERTIA CONTROL (Simula a física exata do Robocode) ---
            double velDesejada = direcaoAcao * 8.0;
            if (velocidadeSimulada < velDesejada) {
                velocidadeSimulada += (velocidadeSimulada < 0) ? 2.0 : 1.0;
                if (velocidadeSimulada > velDesejada) velocidadeSimulada = velDesejada;
            } else if (velocidadeSimulada > velDesejada) {
                velocidadeSimulada -= (velocidadeSimulada > 0) ? 2.0 : 1.0;
                if (velocidadeSimulada < velDesejada) velocidadeSimulada = velDesejada;
            }
            
            // Corrige o ângulo para manter a órbita na onda primária
            direcaoSimulada = Math.atan2(posPrevistaX - ondaPrimaria.origem.x, posPrevistaY - ondaPrimaria.origem.y) + (Math.PI/2) * (direcaoAcao == 0 ? 1 : direcaoAcao);
            
            posPrevistaX += Math.sin(direcaoSimulada) * velocidadeSimulada;
            posPrevistaY += Math.cos(direcaoSimulada) * velocidadeSimulada;

            // Restringe aos limites da arena (Wall-smoothing interno)
            posPrevistaX = Utilitario.limitar(posPrevistaX, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
            posPrevistaY = Utilitario.limitar(posPrevistaY, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);

            // --- Multi-Wave Pathing: Verifica o impacto de TODAS as outras ondas ao longo do caminho
            for (OndaInimiga onda : ondasInimigas) {
                if (onda == ondaPrimaria) continue; 
                
                double distOndaSimulada = (tempoSimulado - onda.tempoDisparo) * onda.velocidadeBala;
                double distRoboOnda = Point2D.distance(onda.origem.x, onda.origem.y, posPrevistaX, posPrevistaY);
                
                // Se cruzarmos com a linha de raio da onda, computamos o risco
                if (Math.abs(distOndaSimulada - distRoboOnda) <= onda.velocidadeBala) {
                    int binOnda = onda.obterBin(posPrevistaX, posPrevistaY);
                    for (int j = -2; j <= 2; j++) {
                        int binAvaliado = (int) Utilitario.limitar(binOnda + j, 0, OndaInimiga.BINS_SURF - 1);
                        riscoPath += bufferSurf[binAvaliado] * (1.0 / (Math.abs(j) + 1));
                    }
                }
            }
        }

        // Calcula o risco da área onde vamos estar quando a onda principal nos atingir
        int binPrimario = ondaPrimaria.obterBin(posPrevistaX, posPrevistaY);
        double riscoFinal = 0;
        for (int i = -2; i <= 2; i++) { // Avalia o bin exato e os vizinhos com decaimento suave (Smearing)
            int binAvaliado = (int) Utilitario.limitar(binPrimario + i, 0, OndaInimiga.BINS_SURF - 1);
            riscoFinal += bufferSurf[binAvaliado] * (1.0 / (Math.abs(i) + 1));
        }

        return riscoFinal + riscoPath; // Somatório do risco de impacto final + risco de cruzar balas secundárias
    }

    // =========================================================
    // LÓGICA DE MOVIMENTO 1 VS 1 (EVASÃO LEGADA FALLBACK)
    // =========================================================
    // Um modo de backup mais simples de movimentação que rebate nos cantos da parede
    // É usado de forma paralela quando a carga de memória do Surfing não for suficiente
    class Movimento_1VS1 {
        private static final double LARGURA_CAMPO = 800;
        private static final double ALTURA_CAMPO = 600;
        private static final double TEMPO_MAX_TENTATIVA = 125;
        private static final double AJUSTE_REVERSA = 0.421075;
        private static final double EVASAO_PADRAO = 1.2;
        private static final double AJUSTE_QUIQUE_PAREDE = 0.699484;
        
        private final AdvancedRobot robô;
        private final Rectangle2D areaDisparo = new Rectangle2D.Double(
            MARGEM_PAREDE, MARGEM_PAREDE,
            LARGURA_CAMPO - MARGEM_PAREDE * 2, ALTURA_CAMPO - MARGEM_PAREDE * 2
        );
        private double direcao = 0.4;
        
        Movimento_1VS1(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public void onScannedRobot(ScannedRobotEvent e) {
            Robo inimigo = new Robo();
            inimigo.anguloAbsolutoRadianos = robô.getHeadingRadians() + e.getBearingRadians();
            inimigo.distancia = e.getDistance();
            
            Point2D posicaoRobo = new Point2D.Double(robô.getX(), robô.getY());
            Point2D posicaoInimigo = Utilitario.projetar(posicaoRobo, inimigo.anguloAbsolutoRadianos, inimigo.distancia);
            Point2D destinoRobo;
            
            double tempoTentativa = 0;
            
            // Simula onde o robô quer ir, diminuindo a distância se encontrar parede (Wall Smoothing)
            while (!areaDisparo.contains(destinoRobo = Utilitario.projetar(
                    posicaoInimigo, 
                    inimigo.anguloAbsolutoRadianos + Math.PI + direcao,
                    inimigo.distancia * (EVASAO_PADRAO - tempoTentativa / 100.0))) 
                    && tempoTentativa < TEMPO_MAX_TENTATIVA) {
                tempoTentativa++;
            }
                
            // Regra de inversão de direção dinâmica e esquiva de parede
            if ((Math.random() < (Rules.getBulletSpeed(potenciaTiroCorrente) / AJUSTE_REVERSA) / inimigo.distancia ||
                    tempoTentativa > (inimigo.distancia / Rules.getBulletSpeed(potenciaTiroCorrente) / AJUSTE_QUIQUE_PAREDE))) {
                direcao = -direcao;
            }
                
            double angulo = Utilitario.anguloAbsoluto(posicaoRobo, destinoRobo) - robô.getHeadingRadians();
            robô.setAhead(Math.cos(angulo) * 100);
            robô.setTurnRightRadians(Math.tan(angulo));
        }
    }

    // =========================================================
    // LÓGICA DE TIRO E VIRTUAL GUNS (GUESSFACTOR + KNN)
    // =========================================================
    // Representa a "Onda de Tiro" disparada pelo nosso robô em direção ao inimigo
    class Onda extends Condition {
        Point2D posicaoAlvo; 
        public Robo alvoOnda; 
        double potenciaTiro;
        Point2D posicaoCanhao;
        double angulo; // Angulo em que o inimigo estava na hora do disparo
        double direcaoLateralOnda;
        
        private static final double DISTANCIA_MAXIMA = 900;
        private int[] buffer; // O buffer específico segmentado para esse disparo
        private double distanciaPercorrida;
        public double pesoImpacto = 5.0; 
        
        // Memória dos "votos" (previsões) de cada uma das nossas armas virtuais
        public int binVotoAuxiliar = -1; 
        public int binVotoARMA = -1;
        public int binVotoARIMA = -1;
        public int binVotoRNA = -1;
        public int binVotoDC = -1;
        public int binVotoAntiTremidinha = -1;
        public int binVotoMedia = -1;
        public int binVotoGunWave = -1; // GunWave atira onde o GuessFactor puramente estatístico manda
        public int binVotoKNN = -1;     // Machine Learning baseado em padrões de atributos
        
        public double[] featuresKNN; // Estado do inimigo para alimentar a IA

        private final AdvancedRobot robô;

        Onda(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        // Método executado todo o tick pelo Robocode para checar a condição da onda
        public boolean test() {
            distanciaPercorrida += Rules.getBulletSpeed(potenciaTiro);
            
            // Se a nossa bala (virtual) alcançou o inimigo
            if (distanciaPercorrida > posicaoCanhao.distance(posicaoAlvo) - MARGEM_PAREDE) {
                // Calcula em qual "Bin" (fatia de ângulo) o inimigo estava realmente no momento que a bala cruzou
                int binCorreto = (int) Math.round((Utils.normalRelativeAngle(Utilitario.anguloAbsoluto(posicaoCanhao, posicaoAlvo) - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
                binCorreto = (int) Utilitario.limitar(binCorreto, 0, BINS - 1);
                
                int pesoBase = (int)Math.round(10 * pesoImpacto);
                
                // Distribui os pontos na memória, dando 10 para o bin exato, e menos para os arredores
                for (int i = 0; i < BINS; i++) {
                    double distanciaBin = Math.abs(binCorreto - i);
                    if (distanciaBin <= 5) { 
                        buffer[i] += (int) Math.round(pesoBase / (Math.pow(2, distanciaBin)));
                    }
                }
                
                // Avalia o desempenho das Armas Virtuais neste tiro
                if (alvoOnda != null) {
                    if (featuresKNN != null) {
                        // Salva os padrões do inimigo no banco de Machine Learning
                        alvoOnda.historicoKNN_features.add(featuresKNN);
                        alvoOnda.historicoKNN_bins.add(binCorreto);
                        if (alvoOnda.historicoKNN_features.size() > 30000) { 
                            alvoOnda.historicoKNN_features.remove(0); // Evita estourar a memória limitando a 30k amostras
                            alvoOnda.historicoKNN_bins.remove(0);
                        }
                    }

                    // Se a Virtual Gun X previu um ângulo muito perto do ângulo correto, ela ganha pontos
                    if (binVotoAuxiliar != -1 && Math.abs(binCorreto - binVotoAuxiliar) <= 2) alvoOnda.acertosVirtualGuns[0]++;
                    if (binVotoARMA != -1 && Math.abs(binCorreto - binVotoARMA) <= 2) alvoOnda.acertosVirtualGuns[1]++;
                    if (binVotoARIMA != -1 && Math.abs(binCorreto - binVotoARIMA) <= 2) alvoOnda.acertosVirtualGuns[2]++;
                    if (binVotoRNA != -1 && Math.abs(binCorreto - binVotoRNA) <= 2) alvoOnda.acertosVirtualGuns[3]++;
                    if (binVotoDC != -1 && Math.abs(binCorreto - binVotoDC) <= 2) alvoOnda.acertosVirtualGuns[4]++;
                    if (binVotoAntiTremidinha != -1 && Math.abs(binCorreto - binVotoAntiTremidinha) <= 2) alvoOnda.acertosVirtualGuns[5]++;
                    if (binVotoMedia != -1 && Math.abs(binCorreto - binVotoMedia) <= 2) alvoOnda.acertosVirtualGuns[6]++;
                    if (binVotoKNN != -1 && Math.abs(binCorreto - binVotoKNN) <= 2) alvoOnda.acertosVirtualGuns[7]++;
                    if (binVotoGunWave != -1 && Math.abs(binCorreto - binVotoGunWave) <= 2) alvoOnda.acertosVirtualGuns[8]++;
                    
                    // Fator de esquecimento (Roll-off): O que ocorreu mais recentemente importa mais.
                    for (int j = 0; j < 9; j++) {
                        alvoOnda.acertosVirtualGuns[j] *= 0.95; 
                    }
                }

                // Destrói a onda pois ela já foi processada
                robô.removeCustomEvent(this);
            }
            return false;
        }

        // --- SISTEMA DE MACHINE LEARNING KNN (K-Nearest Neighbors) ---
        // Ele varre todo o passado e encontra quais momentos foram estatisticamente mais parecidos com o momento atual.
        public void registrarMiraKNNPesado(Robo inimigo, Robo meuRobo) {
            if (inimigo.historicoKNN_features.size() < 10 || featuresKNN == null) return;
            
            // Define o tamanho da memória a pesquisar (mais profundo contra Surfers, pois eles tentam nos enganar)
            int limiteProfundidade = inimigo.classificadoComoSurfer ? inimigo.historicoKNN_features.size() : Math.min(500, inimigo.historicoKNN_features.size());
            int n = limiteProfundidade;
            
            // K (Quantos vizinhos considerar) é baseado no tamanho do banco de dados
            int K = (int) Math.min(50, Math.sqrt(n)); 
            double[] topDist = new double[K];
            int[] topBins = new int[K];
            Arrays.fill(topDist, Double.MAX_VALUE);
            
            // Varre o histórico e compara cada situação com a atual (Features)
            for (int i = inimigo.historicoKNN_features.size() - n; i < inimigo.historicoKNN_features.size(); i++) {
                double[] hist = inimigo.historicoKNN_features.get(i);
                double d = 0;
                double diff;
                
                // Distância Euclidiana Ponderada: Compara os atributos de estado e soma os erros
                diff = (hist[0] - featuresKNN[0]) / 8.0;   d += diff * diff * 2.0;   // Velocidade
                diff = (hist[1] - featuresKNN[1]) / 0.1;   d += diff * diff * 3.0;   // Mudança de direção (Curva)
                diff = (hist[2] - featuresKNN[2]) / 1000.0; d += diff * diff * 1.0;  // Distância
                diff = (hist[3] - featuresKNN[3]) / 8.0;   d += diff * diff * 2.5;   // Velocidade Lateral
                diff = (hist[4] - featuresKNN[4]) / 2.0;   d += diff * diff * 1.5;   // Aceleração
                diff = (hist[5] - featuresKNN[5]) / 100.0;  d += diff * diff * 1.0;  // Tempo desde a última curva
                
                // Ordenação por inserção para manter os K melhores matches
                if (d < topDist[K-1]) {
                    int j = K - 1;
                    while(j > 0 && d < topDist[j-1]) {
                        topDist[j] = topDist[j-1];
                        topBins[j] = topBins[j-1];
                        j--;
                    }
                    topDist[j] = d;
                    topBins[j] = inimigo.historicoKNN_bins.get(i); // Guarda para onde ele fugiu naquela ocasião similar
                }
            }
            
            // Distribuição de Kernel Gaussiano (vizinhos mais próximos têm muito mais peso no voto)
            double[] binsWeights = new double[BINS];
            for (int i = 0; i < K; i++) {
                if (topDist[i] == Double.MAX_VALUE) break;
                double weight = 1.0 / (1.0 + topDist[i]);
                binsWeights[topBins[i]] += weight;
            }
            
            // Escolhe o Bin mais pesado como sendo a resposta/voto do KNN
            int bestBin = BIN_CENTRAL;
            double maxW = -1;
            for (int i = 0; i < BINS; i++) {
                if (binsWeights[i] > maxW) {
                    maxW = binsWeights[i];
                    bestBin = i;
                }
            }
            binVotoKNN = bestBin;
        }
        
        // Mira básica preditiva: Simula a trajetória linear e circular (Head-on, Linear e Circular)
        public void registrarMirasAuxiliares(Robo inimigo, Robo meuRobo, double tempoEstimado) {
            double inimigoX = meuRobo.x + inimigo.distancia * Math.sin(inimigo.anguloAbsolutoRadianos);
            double inimigoY = meuRobo.y + inimigo.distancia * Math.cos(inimigo.anguloAbsolutoRadianos);
            
            double angulo1 = inimigo.anguloAbsolutoRadianos; // Head-On (No corpo atual)
            
            // Predição Linear
            double linX = inimigoX + Math.sin(inimigo.direcao) * inimigo.velocidade * tempoEstimado;
            double linY = inimigoY + Math.cos(inimigo.direcao) * inimigo.velocidade * tempoEstimado;
            double angulo2 = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(linX, linY));
            
            // Predição Circular
            double deltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            double circX, circY;
            if (Math.abs(deltaDir) < 0.00001) {
                circX = linX; circY = linY;
            } else {
                circX = inimigoX + (inimigo.velocidade / deltaDir) * (Math.cos(inimigo.direcao) - Math.cos(inimigo.direcao + deltaDir * tempoEstimado));
                circY = inimigoY + (inimigo.velocidade / deltaDir) * (Math.sin(inimigo.direcao + deltaDir * tempoEstimado) - Math.sin(inimigo.direcao));
            }
            double angulo3 = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(circX, circY));
            
            // Tira a média trigonométrica dos três e converte em bin
            double mediaSeno = (Math.sin(angulo1) + Math.sin(angulo2) + Math.sin(angulo3)) / 3.0;
            double mediaCosseno = (Math.cos(angulo1) + Math.cos(angulo2) + Math.cos(angulo3)) / 3.0;
            double anguloMedio = Math.atan2(mediaSeno, mediaCosseno);
            
            double offsetMedio = Utils.normalRelativeAngle(anguloMedio - angulo);
            int binMedia = (int) Math.round((offsetMedio / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            
            binVotoAuxiliar = (int) Utilitario.limitar(binMedia, 0, BINS - 1);
        }

        // --- MODELAGEM ESTATÍSTICA DE SÉRIES TEMPORAIS: ARMA / ARIMA ---
        // Calcula a autocorrelação dos movimentos (como se prevíssemos ações financeiras ou clima)
        public void registrarMirasARMA_ARIMA(Robo inimigo, Robo meuRobo, double tempoEstimado, int qtdInimigosVivos) {
            if (inimigo.historicoVelocidade.size() < 5) return; 
            
            int profundidadeMaxima = (qtdInimigosVivos > 2) ? 5 : ((qtdInimigosVivos == 2) ? 15 : 40);
            int n = Math.min(inimigo.historicoVelocidade.size(), profundidadeMaxima);
            
            double iniX = meuRobo.x + inimigo.distancia * Math.sin(inimigo.anguloAbsolutoRadianos);
            double iniY = meuRobo.y + inimigo.distancia * Math.cos(inimigo.anguloAbsolutoRadianos);
            double velBala = Rules.getBulletSpeed(this.potenciaTiro);
            
            // Cálculo da média (mu)
            double mu_v = 0, mu_d = 0;
            for(int i = 0; i < n; i++) {
                mu_v += inimigo.historicoVelocidade.get(i);
                mu_d += inimigo.historicoDeltaDirecao.get(i);
            }
            mu_v /= n; 
            mu_d /= n;
            
            // Cálculo de Covariância (lag 1)
            double c0_v = 0, c1_v = 0, c0_d = 0, c1_d = 0;
            for(int i = 0; i < n - 1; i++) {
                double diff_v_i = inimigo.historicoVelocidade.get(i) - mu_v;
                double diff_v_i1 = inimigo.historicoVelocidade.get(i+1) - mu_v;
                c0_v += diff_v_i * diff_v_i;
                c1_v += diff_v_i * diff_v_i1;

                double diff_d_i = inimigo.historicoDeltaDirecao.get(i) - mu_d;
                double diff_d_i1 = inimigo.historicoDeltaDirecao.get(i+1) - mu_d;
                c0_d += diff_d_i * diff_d_i;
                c1_d += diff_d_i * diff_d_i1;
            }
            c0_v += Math.pow(inimigo.historicoVelocidade.get(n-1) - mu_v, 2);
            c0_d += Math.pow(inimigo.historicoDeltaDirecao.get(n-1) - mu_d, 2);

            // Coeficiente Autoregressivo Estacionário
            double phi_v = (c0_v == 0) ? 0 : Utilitario.limitar(c1_v / c0_v, -0.99, 0.99);
            double phi_d = (c0_d == 0) ? 0 : Utilitario.limitar(c1_d / c0_d, -0.99, 0.99);

            // Resíduos e Theta de Médias Móveis
            double erro_v = inimigo.historicoVelocidade.get(0) - (mu_v + phi_v * (inimigo.historicoVelocidade.get(1) - mu_v));
            double erro_d = inimigo.historicoDeltaDirecao.get(0) - (mu_d + phi_d * (inimigo.historicoDeltaDirecao.get(1) - mu_d));
            double theta_v = 0.5; 
            double theta_d = 0.5;

            // Simulação Tick-a-Tick no espaço-tempo para a Arma ARMA
            double simArmaX = iniX, simArmaY = iniY;
            double simArmaDir = inimigo.direcao;
            double simArmaVel = inimigo.velocidade;
            double simArmaDeltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            int tArma = 0;
            
            while (Point2D.distance(meuRobo.x, meuRobo.y, simArmaX, simArmaY) > tArma * velBala && tArma < 150) {
                tArma++;
                simArmaVel = mu_v + phi_v * (simArmaVel - mu_v);
                simArmaDeltaDir = mu_d + phi_d * (simArmaDeltaDir - mu_d);
                if (tArma == 1) { // Aplica correção do resíduo (Choque na média móvel)
                    simArmaVel += theta_v * erro_v;
                    simArmaDeltaDir += theta_d * erro_d;
                }
                
                simArmaDir += simArmaDeltaDir;
                simArmaX += Math.sin(simArmaDir) * simArmaVel;
                simArmaY += Math.cos(simArmaDir) * simArmaVel;
            }
            double anguloARMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArmaX, simArmaY));
            int binARMA = (int) Math.round((Utils.normalRelativeAngle(anguloARMA - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARMA = (int) Utilitario.limitar(binARMA, 0, BINS - 1);
            
            // --- CÁLCULO DA ARMA ARIMA (Integrada de Diferenças) ---
            // Usa as derivadas (diferenças) ao invés do valor absoluto para séries não-estacionárias
            double[] diff_v = new double[n-1];
            double[] diff_d = new double[n-1];
            double mu_dv = 0, mu_dd = 0;
            
            for(int i = 0; i < n - 1; i++) {
                diff_v[i] = inimigo.historicoVelocidade.get(i) - inimigo.historicoVelocidade.get(i+1);
                diff_d[i] = inimigo.historicoDeltaDirecao.get(i) - inimigo.historicoDeltaDirecao.get(i+1);
                mu_dv += diff_v[i];
                mu_dd += diff_d[i];
            }
            if (n > 1) { mu_dv /= (n-1); mu_dd /= (n-1); }

            double c0_dv = 0, c1_dv = 0, c0_dd = 0, c1_dd = 0;
            for(int i = 0; i < n - 2; i++) {
                double d_vi = diff_v[i] - mu_dv;
                double d_vi1 = diff_v[i+1] - mu_dv;
                c0_dv += d_vi * d_vi;
                c1_dv += d_vi * d_vi1;

                double d_di = diff_d[i] - mu_dd;
                double d_di1 = diff_d[i+1] - mu_dd;
                c0_dd += d_di * d_di;
                c1_dd += d_di * d_di1;
            }
            if (n > 2) {
                c0_dv += Math.pow(diff_v[n-2] - mu_dv, 2);
                c0_dd += Math.pow(diff_d[n-2] - mu_dd, 2);
            }

            double phi_dv = (c0_dv == 0) ? 0 : Utilitario.limitar(c1_dv / c0_dv, -0.99, 0.99);
            double phi_dd = (c0_dd == 0) ? 0 : Utilitario.limitar(c1_dd / c0_dd, -0.99, 0.99);

            double erro_dv = (n > 1) ? diff_v[0] - (mu_dv + phi_dv * ((n > 2 ? diff_v[1] : 0) - mu_dv)) : 0;
            double erro_dd = (n > 1) ? diff_d[0] - (mu_dd + phi_dd * ((n > 2 ? diff_d[1] : 0) - mu_dd)) : 0;

            double simArimaX = iniX, simArimaY = iniY;
            double simArimaVel = inimigo.velocidade;
            double simArimaDir = inimigo.direcao;
            double simArimaDeltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            
            double simArimaDiffVel = (n > 1) ? diff_v[0] : 0;
            double simArimaDiffDeltaDir = (n > 1) ? diff_d[0] : 0;
            int tArima = 0;
            
            while (Point2D.distance(meuRobo.x, meuRobo.y, simArimaX, simArimaY) > tArima * velBala && tArima < 150) {
                tArima++;
                
                simArimaDiffVel = mu_dv + phi_dv * (simArimaDiffVel - mu_dv);
                simArimaDiffDeltaDir = mu_dd + phi_dd * (simArimaDiffDeltaDir - mu_dd);
                if (tArima == 1) {
                    simArimaDiffVel += theta_v * erro_dv;
                    simArimaDiffDeltaDir += theta_d * erro_dd;
                }

                simArimaVel = Utilitario.limitar(simArimaVel + simArimaDiffVel, -8, 8); 
                simArimaDeltaDir += simArimaDiffDeltaDir; 
                simArimaDir += simArimaDeltaDir;
                
                simArimaX += Math.sin(simArimaDir) * simArimaVel;
                simArimaY += Math.cos(simArimaDir) * simArimaVel;
            }
            double anguloARIMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArimaX, simArimaY));
            int binARIMA = (int) Math.round((Utils.normalRelativeAngle(anguloARIMA - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARIMA = (int) Utilitario.limitar(binARIMA, 0, BINS - 1);
        }
        
        // Método cérebro: Avalia quem atira melhor e mistura os votos para retornar o ângulo exato do canhão
        double offsetAnguloMaisVisitado() {
            BT_7274 bot = (BT_7274) robô;
            
            int maisVisitado = BIN_CENTRAL;
            double maiorVoto = -1; 
            
            int roundAtual = bot.getRoundNum();
            
            // O Júri: Avalia a precisão interna das armas apenas uma vez por round para salvar CPU
            if (BT_7274.ultimoRoundAvaliacao != roundAtual) {
                int melhorVGDestaAvaliacao = -1;
                double maxAcertosVG = -1.0; 
                
                if (bot.alvo != null && bot.getOthers() <= 1) {
                    for (int j = 0; j < 9; j++) { 
                        
                        // Perfilador de eficácia: Cada arma é bonificada dependendo do tipo de inimigo
                        double pontuacaoAvaliada = bot.alvo.acertosVirtualGuns[j];
                        
                        boolean ehAvancado = !bot.alvo.classificadoComoBasico || bot.alvo.classificadoComoSurfer || bot.alvo.reversoesLaterais > 3;
                        
                        if (ehAvancado) {
                            if (j == 0) pontuacaoAvaliada *= 0.2; // Armas preditivas perdem peso contra Surfers
                            if (j == 6) pontuacaoAvaliada *= 0.5; 
                            if (j == 7) pontuacaoAvaliada *= 1.5; // KNN Pesado ganha bônus
                            if (j == 8) pontuacaoAvaliada *= 1.6; // GunWave é letal contra avançados
                            if (j == 3 || j == 4) pontuacaoAvaliada *= 1.2; 
                        } else {
                            if (j == 0) pontuacaoAvaliada *= 1.5; // Auxiliares matam noobs rapidamente
                            if (j == 1 || j == 2) pontuacaoAvaliada *= 1.2; 
                        }
                        
                        if (pontuacaoAvaliada > maxAcertosVG) {
                            maxAcertosVG = pontuacaoAvaliada;
                            melhorVGDestaAvaliacao = j;
                        }
                    }
                }
                
                if (melhorVGDestaAvaliacao != -1) {
                    BT_7274.armaTravada = melhorVGDestaAvaliacao;
                    bot.escolheuArmaNaturalmente = true;
                } else {
                    bot.escolheuArmaNaturalmente = false;
                }
                BT_7274.ultimoRoundAvaliacao = roundAtual;
            }
            
            if (BT_7274.armaTravada != -1) {
                bot.escolheuArmaNaturalmente = true;
            }

            int melhorVG = BT_7274.armaTravada;
            
            // Alternador de emergência: Usa Par/Ímpar do round para ser imprevisível
            int armaAlternada = (bot.getRoundNum() % 2 == 0) ? 7 : 8;
            
            // --- TRAVA DE DESPERDÍCIO --- Se o alvo for noob não gastamos o KNN nem o GunWave
            if (bot.alvo != null && bot.alvo.classificadoComoBasico && !bot.alvo.classificadoComoSurfer) {
                armaAlternada = 7; // Volta para um fallback aceitável
            }
            
            // Lógicas de trava defensiva e trava de perseguição (LOCKS)
            double precisaoGlobalOnda = bot.totalTirosDisparados > 0 ? ((double)bot.totalTirosAcertados / bot.totalTirosDisparados) * 100.0 : 100.0;
            boolean forcarPorPrecisao = (bot.totalTirosDisparados >= 15 && precisaoGlobalOnda < 10.0);
            boolean forcarPorDerrotas = (bot.alvo != null && bot.alvo.nome != null && BT_7274.derrotasSeguidas.getOrDefault(bot.alvo.nome, 0) > 5);
            
            boolean ehAvancadoLock = !bot.alvo.classificadoComoBasico || bot.alvo.classificadoComoSurfer || bot.alvo.reversoesLaterais > 3;
            boolean surfing1v1DedicadoAtivo = (bot.getOthers() <= 1 && ehAvancadoLock);
            
            // Acoplamento Wave-to-Wave: Se estou surfando, devo atirar com a lógica de ondas também
            if (surfing1v1DedicadoAtivo) {
                melhorVG = 8; 
                bot.escolheuArmaNaturalmente = false;
            } else if (forcarPorDerrotas) {
                melhorVG = armaAlternada; 
                bot.escolheuArmaNaturalmente = false;
            } else if (bot.alvo != null && bot.alvo.classificadoComoSurfer && (melhorVG == -1 || melhorVG < 3)) {
                // Se a mira base escolhida não dá conta do recado, aciona a arma avançada correspondente ao round
                if (bot.alvo.agressividade > 2.0) {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                } else {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                }
            } else if (forcarPorPrecisao) {
                melhorVG = armaAlternada; 
                bot.escolheuArmaNaturalmente = false;
            } else if (melhorVG == -1) { // Quando há incerteza absoluta
                if (BT_7274.roundsSemEscolha >= 4) {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                }
            } else {
                bot.escolheuArmaNaturalmente = true; 
            }
            
            bot.ultimaVGEscolhida = melhorVG;
            
            // Escolhe a resposta pura do GunWave (Maior quantidade bruta no Buffer dinâmico)
            int melhorBinGunWave = BIN_CENTRAL;
            double maxBuffer = -1;
            for(int i = 0; i < BINS; i++) {
                if(buffer[i] > maxBuffer) {
                    maxBuffer = buffer[i];
                    melhorBinGunWave = i;
                }
            }
            binVotoGunWave = melhorBinGunWave;
            
            // Computa os votos e aplica pesos absurdos caso a arma seja a escolhida ativa
            for (int i = 0; i < BINS; i++) {
                double votos = buffer[i];
                if (i == binVotoAuxiliar) votos += (10.0 * pesoImpacto); 
                
                if (bot.getOthers() <= 1 && melhorVG != -1) {
                    double superPeso = 25000.0 * pesoImpacto; // Esmaga outros pesos para garantir a trava
                    if (melhorVG == 0 && i == binVotoAuxiliar) votos += superPeso;
                    if (melhorVG == 1 && i == binVotoARMA) votos += superPeso;
                    if (melhorVG == 2 && i == binVotoARIMA) votos += superPeso;
                    if (melhorVG == 3 && i == binVotoRNA) votos += superPeso;
                    if (melhorVG == 4 && i == binVotoDC) votos += superPeso;
                    if (melhorVG == 5 && i == binVotoAntiTremidinha) votos += superPeso;
                    if (melhorVG == 6 && i == binVotoMedia) votos += superPeso;
                    if (melhorVG == 7 && i == binVotoKNN) votos += superPeso;
                    if (melhorVG == 8 && i == binVotoGunWave) votos += superPeso; 
                }
                
                // Pesos fracos para manter a compatibilidade orgânica com as antigas ARMA e ARIMA
                if (bot.alvo != null && bot.alvo.classificadoComoBasico) {
                    if (i == binVotoARMA) votos += (10000.0 * pesoImpacto);
                    if (i == binVotoARIMA) votos += (10000.0 * pesoImpacto);
                } else {
                    if (i == binVotoARMA) votos += (8.0 * pesoImpacto);
                    if (i == binVotoARIMA) votos += (8.0 * pesoImpacto);
                }
                
                if (votos > maiorVoto) {
                    maiorVoto = votos;
                    maisVisitado = i;
                }
            }
            // Converte de Bin para Radianos e retorna para a mira do canhão
            return (direcaoLateralOnda * LARGURA_BIN) * (maisVisitado - BIN_CENTRAL);
        }
        
        // Pega os dados do inimigo e encontra a "tabela" correta nos buffers segmentados multidimensionais
        void definirSegmentacoes(double distancia, double velocidade, double ultimaVelocidade) {
            int indiceDistancia = (int) Math.min(INDICES_DISTANCIA - 1, distancia / (DISTANCIA_MAXIMA / INDICES_DISTANCIA));
            int indiceVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(velocidade / 2));
            int indiceUltimaVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(ultimaVelocidade / 2));
            buffer = buffersEstatisticos[indiceDistancia][indiceVelocidade][indiceUltimaVelocidade];
        }
    }
    
    // =========================================================
    // ESTÉTICA E CORES (Tema Oficial do Titã BT-7274)
    // =========================================================
    private void coresBT7274() {
        setColors(new Color(60, 80, 40), new Color(255, 120, 0), new Color(100, 100, 100), 
                  new Color(255, 120, 0), new Color(255, 120, 0));
    }

    // =========================================================
    // LOOP PRINCIPAL (RUN) - O coração rítmico do Robô
    // =========================================================
    public void run() {
        campoBatalha.height = getBattleFieldHeight();
        campoBatalha.width = getBattleFieldWidth();
        
        meuRobo.x = getX();
        meuRobo.y = getY();
        meuRobo.energia = getEnergy();
        
        pontoAlvo.x = meuRobo.x;
        pontoAlvo.y = meuRobo.y;
        
        alvo = new Robo();
        alvo.vivo = false;
        
        // Separa fisicamente o radar, o chassi e a arma (movimento independente)
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true); 
        
        if (getOthers() > 1) { // MODO MELEE (Tumulto / Muitas naves)
            atualizarListaPosicoes(QUANTIDADE_PONTOS_PREVISTOS * 3); // Escaneia o mapa todo
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY); // Gira rápido para atualizar todos
            
            while (true) {
                // Atualiza a física básica a cada tick
                meuRobo.ultimaDirecao = meuRobo.direcao;
                meuRobo.direcao = getHeadingRadians();
                meuRobo.x = getX();
                meuRobo.y = getY();
                meuRobo.energia = getEnergy();
                meuRobo.anguloCanhaoRadianos = getGunHeadingRadians();
                
                verificarFuga(); 
                
                // Limpeza de memória (Remove quem já morreu há 25 ticks)
                Iterator<Robo> iteradorInimigos = listaInimigos.values().iterator();
                while (iteradorInimigos.hasNext()) {
                    Robo r = iteradorInimigos.next();
                    if (getTime() - r.tempoVarredura > 25) {
                        r.vivo = false;
                        if (alvo.nome != null && r.nome.equals(alvo.nome))
                            alvo.vivo = false;
                    }
                }
                
                movimento(); // Pensa pra onde vai
                
                if (alvo.vivo) {
                    disparar(); // Pensa onde vai atirar
                }
                execute(); // Envia as ordens para o Motor Java do Robocode
            }
        } else { // MODO 1v1 (Duelo Mortal)
            direcaoLateral = 1;
            velocidadeInimigoAnterior = 0;
            
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY); 
            
            while (true) {
                // Radar "Lock" perfeito, gruda a visão no adversário
                if (getRadarTurnRemaining() == 0.0) {
                    setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
                }
                execute();
            }
        }
    }

    // =========================================================
    // EVENTOS DO ROBÔ & PERFILAMENTO DE ESTRATÉGIA (Olhos e Ouvidos do BT-7274)
    // =========================================================
    public void onScannedRobot(ScannedRobotEvent e) {
        coresBT7274();
        
        if (getOthers() > 1) { // === LÓGICA QUANDO ESTÁ NO MELEE ===
            Robo inimigo = listaInimigos.get(e.getName());
            if (inimigo == null) {
                inimigo = new Robo();
                listaInimigos.put(e.getName(), inimigo);
            }
            
            // Classifica Sit&Bots (Quem fica só parado na arena)
            if (Math.abs(e.getVelocity()) < 1.5) {
                inimigo.ticksParado++;
            } else {
                inimigo.ticksParado = 0;
            }
            
            // --- DETECTOR DE ENERGIA (Alerta de Tiro!) ---
            // Se o alvo perdeu entre 0.1 e 3.0 de energia sem bater, ele atirou!
            double quedaEnergia = inimigo.energiaAnterior - e.getEnergy();
            if (quedaEnergia > 0 && quedaEnergia <= 3) {
                inimigo.agressividade += 0.1; // Marca ele como inimigo agressivo
                
                TiroInimigo novoTiro = new TiroInimigo();
                novoTiro.origem = new Point2D.Double(
                    meuRobo.x + e.getDistance() * Math.sin(getHeadingRadians() + e.getBearingRadians()),
                    meuRobo.y + e.getDistance() * Math.cos(getHeadingRadians() + e.getBearingRadians())
                );
                novoTiro.velocidade = Rules.getBulletSpeed(quedaEnergia);
                novoTiro.angulo = Utilitario.anguloAbsoluto(novoTiro.origem, meuRobo);
                novoTiro.tempoDisparo = getTime();
                tirosSuspeitos.add(novoTiro); // Entra na lista de balas para evadir
            }
            inimigo.energiaAnterior = e.getEnergy();
            
            // Dinâmica de prioridade no caos: Atacar quem tem muita vida perto, ou pouca vida longe.
            inimigo.fatorAmeaca = (e.getEnergy() / Math.max(1, meuRobo.energia)) + inimigo.agressividade;
            if (e.getDistance() < 250) inimigo.fatorAmeaca *= 1.5; 

            // Atualiza os dados matemáticos do alvo para usarmos nos cálculos futuros
            inimigo.anguloAbsolutoRadianos = e.getBearingRadians();
            inimigo.setLocation(new Point2D.Double(
                    meuRobo.x + e.getDistance() * Math.sin(getHeadingRadians() + inimigo.anguloAbsolutoRadianos),
                    meuRobo.y + e.getDistance() * Math.cos(getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            inimigo.ultimaDirecao = inimigo.direcao;
            inimigo.nome = e.getName();
            inimigo.energia = e.getEnergy();
            inimigo.vivo = true;
            inimigo.tempoVarredura = getTime();
            inimigo.velocidade = e.getVelocity();
            inimigo.direcao = e.getHeadingRadians();
            
            // Popula listas da Rede Neural de tempo de disparo
            inimigo.historicoVelocidade.addFirst(inimigo.velocidade);
            inimigo.historicoDeltaDirecao.addFirst(inimigo.direcao - inimigo.ultimaDirecao);
            if (inimigo.historicoVelocidade.size() > 50) inimigo.historicoVelocidade.removeLast();
            if (inimigo.historicoDeltaDirecao.size() > 50) inimigo.historicoDeltaDirecao.removeLast();
            
            // Verifica se o Inimigo não sabe esquivar e fica grudado na parede ("Clinger") ou correndo em linha ("Linear")
            double distParedeInimigo = Math.min(
                Math.min(inimigo.x, campoBatalha.width - inimigo.x), 
                Math.min(inimigo.y, campoBatalha.height - inimigo.y)
            );
            boolean isClinger = distParedeInimigo < 60; 
            boolean isLinear = inimigo.reversoesLaterais < 2 && inimigo.historicoVelocidade.size() >= 30;
            
            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
            } else {
                inimigo.classificadoComoBasico = false;
            }
            
            // --- DETECTOR DE REVERSÕES LATERAIS ---
            double velLateral = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            if (Math.abs(velLateral) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateral) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++; // Se a direção atual inverteu da antiga, soma reversão
                    inimigo.ticksDesdeReversao = 0;
                } else {
                    inimigo.ticksDesdeReversao++;
                }
            } else {
                inimigo.ticksDesdeReversao++;
            }
            inimigo.ultimaVelocidadeLateral = velLateral;
            
            // --- CÉREBRO: PROFILER DE COMPORTAMENTO ---
            // Se ele nos circula como uma lua e tenta enganar nossas balas, ELE É UM SURFER DE ELITE.
            double perpendicularidade = Math.abs(Math.cos(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            if (perpendicularidade < 0.6 && inimigo.saldoPrecisao < -1.0 && inimigo.reversoesLaterais > 2) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.1);
            } else {
                inimigo.fatorSurf = Math.max(0.0, inimigo.fatorSurf - 0.02);
            }
            
            if (inimigo.fatorSurf > 0.4) {
                inimigo.classificadoComoSurfer = true;
            } else if (inimigo.fatorSurf == 0.0) {
                inimigo.classificadoComoSurfer = false;
            }
            
            // Sistema Culling: Penaliza na prioridade alvos difíceis. Focamos no mais fácil/próximo.
            inimigo.pontuacaoDisparo = inimigo.energia < 25 ? (inimigo.energia < 5 ?
                    (inimigo.energia == 0 ? Double.MIN_VALUE : inimigo.distance(meuRobo) * 0.1) :
                    inimigo.distance(meuRobo) * 0.75) : inimigo.distance(meuRobo);
                    
            inimigo.pontuacaoDisparo -= (inimigo.saldoPrecisao * 25.85); // Pune inimigos que atiram melhor
            
            // Se o alvo for Elite, fugimos dele pra caçar noobs primeiro (em Melee)
            boolean isAmeacaAvancada = (inimigo.agressividade > 0.5 || inimigo.saldoPrecisao > 10.0 || inimigo.classificadoComoSurfer);
            double raioCaca = (getOthers() > 1) ? 800 : 450; 
            if (isAmeacaAvancada && inimigo.distance(meuRobo) <= raioCaca) {
                inimigo.pontuacaoDisparo -= 100000.0; 
            }
                    
            if (getOthers() == 1) {
                setTurnRadarLeftRadians(getRadarTurnRemainingRadians()); // Lock fino no 1v1
            }
            
            // Decide se altera o foco para este inimigo
            if (!alvo.vivo || inimigo.pontuacaoDisparo < alvo.pontuacaoDisparo) {
                alvo = inimigo;
            }
        }
        else { // === LÓGICA QUANDO ESTÁ NO MODO DE DUELO (1V1) ===
            setScanColor(Color.red); // Olho vermelho para mostrar raiva
            Robo inimigo = listaInimigos.get(e.getName());
            if (inimigo == null) {
                inimigo = new Robo();
                inimigo.nome = e.getName();
                listaInimigos.put(e.getName(), inimigo);
            }

            // Exatamente a mesma atualização de estado físico e angular do Melee
            inimigo.anguloAbsolutoRadianos = getHeadingRadians() + e.getBearingRadians();
            inimigo.distancia = e.getDistance();
            inimigo.velocidade = e.getVelocity();
            inimigo.direcao = e.getHeadingRadians(); 
            inimigo.ultimaDirecao = velocidadeInimigoAnterior == 0 ? inimigo.direcao : (e.getHeadingRadians() - (e.getVelocity() - velocidadeInimigoAnterior)); 
            
            if (Math.abs(e.getVelocity()) < 1.5) {
                inimigoTicksParado_1v1++;
            } else {
                inimigoTicksParado_1v1 = 0;
            }
            
            inimigo.setLocation(Utilitario.projetar(new Point2D.Double(getX(), getY()), inimigo.anguloAbsolutoRadianos, inimigo.distancia));
            
            inimigo.historicoVelocidade.addFirst(inimigo.velocidade);
            inimigo.historicoDeltaDirecao.addFirst(inimigo.direcao - inimigo.ultimaDirecao);
            if (inimigo.historicoVelocidade.size() > 50) inimigo.historicoVelocidade.removeLast();
            if (inimigo.historicoDeltaDirecao.size() > 50) inimigo.historicoDeltaDirecao.removeLast();
            
            // Repete o perfilamento do inimigo (Avançado ou Noob) em tempo real
            double distParedeInimigo = Math.min(
                Math.min(inimigo.x, campoBatalha.width - inimigo.x), 
                Math.min(inimigo.y, campoBatalha.height - inimigo.y)
            );
            boolean isClinger = distParedeInimigo < 60; 
            boolean isLinear = inimigo.reversoesLaterais < 2 && inimigo.historicoVelocidade.size() >= 30;
            
            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
            } else {
                inimigo.classificadoComoBasico = false;
            }
            
            double velLateralAtual = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            
            if (Math.abs(velLateralAtual) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateralAtual) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++;
                    inimigo.ticksDesdeReversao = 0;
                } else {
                    inimigo.ticksDesdeReversao++;
                }
            } else {
                inimigo.ticksDesdeReversao++;
            }
            inimigo.ultimaVelocidadeLateral = velLateralAtual;
            
            double perpendicularidade = Math.abs(Math.cos(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            if (perpendicularidade < 0.6 && inimigo.saldoPrecisao < -1.0 && inimigo.reversoesLaterais > 2) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.1);
            } else {
                inimigo.fatorSurf = Math.max(0.0, inimigo.fatorSurf - 0.02);
            }
            
            if (perpendicularidade < 0.8 && inimigo.reversoesLaterais > 4) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.15);
            }
            if (inimigo.fatorSurf > 0.25 || inimigo.reversoesLaterais > 6) {
                inimigo.classificadoComoSurfer = true;
                inimigo.classificadoComoBasico = false;
            } else if (inimigo.fatorSurf == 0.0 && inimigo.reversoesLaterais < 3) {
                inimigo.classificadoComoSurfer = false;
            }
            
            if (inimigo.velocidade != 0) {
                direcaoLateral = Utilitario.sinal(velLateralAtual);
            }
                
            // --- INICIALIZA A ONDA DE DISPARO (Nosso tiro virtual invisível) ---
            Onda onda = new Onda(this);
            onda.posicaoCanhao = new Point2D.Double(getX(), getY());
            onda.posicaoAlvo = inimigo;
            onda.direcaoLateralOnda = direcaoLateral;
            onda.definirSegmentacoes(inimigo.distancia, inimigo.velocidade, velocidadeInimigoAnterior);
            
            double aceleracaoAtual = inimigo.velocidade - velocidadeInimigoAnterior;
            // Carrega os parâmetros (vetores de estado) que o Machine Learning vai engolir
            onda.featuresKNN = new double[] {
                inimigo.velocidade,
                inimigo.direcao - inimigo.ultimaDirecao,
                inimigo.distancia,
                velLateralAtual,
                aceleracaoAtual,
                inimigo.ticksDesdeReversao
            };
            
            if(alvo != null && alvo.saldoPrecisao > 5) {
                onda.pesoImpacto = 12.0; // Se ele nos machuca, dar prioridade no aprendizado
            }
            
            velocidadeInimigoAnterior = inimigo.velocidade;
            onda.angulo = inimigo.anguloAbsolutoRadianos;
            
            // Modulação de força de tiro (Potência inteligente baseada na distância)
            potenciaTiroCorrente = Math.min(3, Math.min(this.getEnergy(), e.getEnergy()) / 4.0);
            if (getEnergy() < 2 && e.getDistance() < 500) {
                potenciaTiroCorrente = 0.1; // Muito fraco, conserva energia
            } else if (e.getDistance() >= 500) {
                potenciaTiroCorrente = 1.1; // Tiro moderado pela longa distância
            }
            if (inimigo.distancia < 150) {
                potenciaTiroCorrente = Math.min(3.0, getEnergy() / 12); // Perto da morte (Shotgun)
            }
            if (alvo != null && alvo.saldoPrecisao > 40.0) {
                potenciaTiroCorrente = Math.max(potenciaTiroCorrente, 2.9); // Mata o maldito antes que mate a gente
            }
            onda.potenciaTiro = potenciaTiroCorrente;
            
            if (inimigoTicksParado_1v1 > 5) {
                // Tiro Direto: Se o cara é noob e não se move, atira sem prever nada
                setTurnGunRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getGunHeadingRadians()));
                if (getEnergy() >= onda.potenciaTiro) {
                    Bullet b = setFireBullet(onda.potenciaTiro);
                    if (b != null) totalTirosDisparados++;
                }
            } else {
                // Tiro Inteligente: Executa os jurados e acha onde atirar
                double tempoEstimadoVoo = inimigo.distancia / Rules.getBulletSpeed(onda.potenciaTiro);
                onda.registrarMirasAuxiliares(inimigo, meuRobo, tempoEstimadoVoo);
                onda.registrarMirasARMA_ARIMA(inimigo, meuRobo, tempoEstimadoVoo, getOthers());
                
                onda.registrarMiraKNNPesado(inimigo, meuRobo);
                
                // Vira a arma pro lado calculado pelo cérebro votador (offsetAnguloMaisVisitado)
                setTurnGunRightRadians(Utils.normalRelativeAngle(
                        inimigo.anguloAbsolutoRadianos - getGunHeadingRadians() + onda.offsetAnguloMaisVisitado()));
                
                // Mensagem visual no console sempre que o cérebro decide mudar de arma ativa
                if (ultimaVGEscolhida != vgAnteriorLog) {
                    if (ultimaVGEscolhida == -1) {
                        System.out.println("[BT-7274] Trocando Arma -> Coletando Dados (Padrão)");
                    } else {
                        String txtAdicional = "";
                        double precisaoGlobalLog = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 100.0;
                        boolean forcarPorDerrotasLog = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
                        boolean ehAvancadoLog = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                        
                        if (getOthers() <= 1 && ehAvancadoLog && !escolheuArmaNaturalmente && ultimaVGEscolhida == 8) {
                            txtAdicional = " (Forçada - Surfing 1v1 Dedicado Ativo!)";
                        } else if (forcarPorDerrotasLog && !escolheuArmaNaturalmente) {
                            txtAdicional = " (Forçada - >5 Derrotas Seguidas! Alternando)";
                        } else if (!escolheuArmaNaturalmente && ultimaVGEscolhida >= 7) {
                            txtAdicional = " (Forçada por Inatividade ou Lock do Inimigo - Alternando)";
                        } else if (totalTirosDisparados >= 15 && precisaoGlobalLog < 10.0 && !escolheuArmaNaturalmente) {
                            txtAdicional = " (Forçada - Precisão Global < 10%! Alternando)"; 
                        }
                        System.out.println("[BT-7274] Trocando Arma -> " + NOMES_VG[ultimaVGEscolhida] + txtAdicional);
                    }
                    vgAnteriorLog = ultimaVGEscolhida;
                }

                Bullet b = setFireBullet(onda.potenciaTiro); // Puxa o gatilho real!
                if (b != null) {
                    totalTirosDisparados++;
                    if (ultimaVGEscolhida != -1) {
                        rastreioBalasVG.put(b, ultimaVGEscolhida); // Mapeia bala disparada para fins métricos de pontuação
                        disparosReaisVG[ultimaVGEscolhida]++;
                    }
                }
                
                if (getEnergy() >= onda.potenciaTiro) {
                    onda.alvoOnda = inimigo; 
                    addCustomEvent(onda); // Lança o Evento Virtual (para verificar debaixo dos panos se teríamos acertado com a arma invisível)
                }
            }
            
            // --- DETECTOR PARA DEFESA DO WAVE SURFING ---
            double quedaEnergia = energiaInimigoAnterior_1v1 - e.getEnergy();
            if (quedaEnergia > 0 && quedaEnergia <= 3.0) {
                inimigo.agressividade += 0.1; // O Inimigo atirou!
                
                // Cria e rastreia na tela a "bala invisível" dele vindo contra nós
                OndaInimiga ondaInimiga = new OndaInimiga();
                ondaInimiga.tempoDisparo = getTime() - 1;
                ondaInimiga.velocidadeBala = Rules.getBulletSpeed(quedaEnergia);
                ondaInimiga.origem = (Point2D.Double) Utilitario.projetar(new Point2D.Double(getX(), getY()), inimigo.anguloAbsolutoRadianos, inimigo.distancia);
                ondaInimiga.anguloDireto = Utilitario.anguloAbsoluto(ondaInimiga.origem, new Point2D.Double(getX(), getY()));
                ondaInimiga.direcaoLateral = Utilitario.sinal(getVelocity() * Math.sin(getHeadingRadians() - ondaInimiga.anguloDireto));
                if (ondaInimiga.direcaoLateral == 0) ondaInimiga.direcaoLateral = 1;
                ondasInimigas.add(ondaInimiga);
            }
            energiaInimigoAnterior_1v1 = e.getEnergy();
                
            movimento1VS1.onScannedRobot(e); // Registra na evasão legada
            
            if (inimigo.distancia < 250) { // Ramming (Abre distância à força caso esteja esmagando o inimigo)
                setTurnRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos + Math.PI/2 - 0.5));
                setAhead(100);
            } else {
                if (habilitarSurfing) {
                    boolean deveSurfar = true;
                    // --- MODO PREDADOR --- Se for Noob, não surfa. Vai direto pro abate c/ MRM.
                    if (alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
                        deveSurfar = false;
                    }
                    if (deveSurfar) {
                        executarSurfing(); 
                    }
                }
            }

            // Centraliza radar lock de volta no oponente
            double anguloRadar = Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getRadarHeadingRadians());
            setTurnRadarRightRadians(anguloRadar * 2.0);
        }
    }

    // =========================================================
    // EVENTOS DE TELEMETRIA E SOBREVIVÊNCIA DE SISTEMA
    // =========================================================
    public void onSkippedTurn(SkippedTurnEvent e) {
        turnosPulados++; // Evitar Skipped Turns é vital, senão a IA falha os ticks precisos.
    }

    // Tira print no final do Round no Console da Java com os Status Totais daquela bateria de lutas
    private void exibirMetricas() {
        if (!escolheuArmaNaturalmente) {
            roundsSemEscolha++;
        } else {
            roundsSemEscolha = 0; 
        }
        
        double hitRate = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 0.0;
        System.out.println("=================================================");
        System.out.println(" RELATÓRIO TÁTICO BT-7274 (FIM DE ROUND)");
        System.out.println("=================================================");
        
        if (ultimaVGEscolhida == -1) {
            System.out.println(" Arma Ativa Final : Coletando Dados (Padrão)");
        } else {
            String sufixo = "";
            boolean forcarPorDerrotasMetrica = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
            boolean ehAvancadoMetrica = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
            
            if (getOthers() <= 1 && ehAvancadoMetrica && !escolheuArmaNaturalmente && ultimaVGEscolhida == 8) sufixo = " (Lock: Surfing 1v1 Dedicado)";
            else if (forcarPorDerrotasMetrica && !escolheuArmaNaturalmente) sufixo = " (Lock Vingança: Alternando)";
            else if (!escolheuArmaNaturalmente && ultimaVGEscolhida >= 7) sufixo = " (Forçada por Inatividade / Surfer - Alternando)";
            else if (totalTirosDisparados >= 15 && hitRate < 10.0 && !escolheuArmaNaturalmente) sufixo = " (Lock Precaução: Precisão < 10% - Alternando)";
            
            System.out.println(" Arma Ativa Final : " + NOMES_VG[ultimaVGEscolhida] + sufixo);
        }
        
        System.out.println(" Tiros Disparados : " + totalTirosDisparados);
        System.out.println(" Tiros Acertados  : " + totalTirosAcertados);
        System.out.println(" HIT RATE GLOBAL  : " + String.format(Locale.US, "%.2f", hitRate) + "%");
        System.out.println(" Turnos Pulados   : " + turnosPulados + " (Skipped Turns)");
        System.out.println(" Rounds Sem Arma  : " + roundsSemEscolha);
        if (alvo != null && alvo.nome != null) {
            System.out.println(" Derrotas p/ Alvo : " + derrotasSeguidas.getOrDefault(alvo.nome, 0));
        }
        
        System.out.println("-------------------------------------------------");
        System.out.println(" PRECISÃO TEÓRICA DAS VTs (Simulação interna):");
        if (alvo != null) {
            for (int i = 0; i < 9; i++) {
                System.out.println(String.format(Locale.US, " [%-15s] Pontuação Virtual: %.4f", NOMES_VG[i], alvo.acertosVirtualGuns[i]));
            }
        } else {
            System.out.println(" Nenhum alvo rastreado para simulação.");
        }

        System.out.println("-------------------------------------------------");
        System.out.println(" PERFORMANCE DAS VIRTUAL GUNS USADAS (1v1):");
        for (int i = 0; i < 9; i++) {
            if (disparosReaisVG[i] > 0) {
                double vgRate = ((double)acertosReaisVG[i] / disparosReaisVG[i]) * 100.0;
                System.out.println(String.format(Locale.US, " [%-15s] Usada: %3d | Acertos: %3d | Precisão: %5.2f%%", 
                    NOMES_VG[i], disparosReaisVG[i], acertosReaisVG[i], vgRate));
            }
        }
        
        System.out.println("-------------------------------------------------");
        System.out.println(" PERFIL DOS INIMIGOS RASTREADOS:");
        for (Robo r : listaInimigos.values()) {
            String tipo = "Intermediário";
            if (r.classificadoComoSurfer) tipo = "SURFER (Avançado)";
            else if (r.classificadoComoBasico) tipo = "Básico (Padrão)";
            
            System.out.println(String.format(Locale.US, " [%-15s] Tipo: %-17s | Rev. Lat: %3d | Agr: %5.2f | Prec: %5.2f", 
                r.nome != null ? r.nome : "Desconhecido", tipo, r.reversoesLaterais, r.agressividade, r.saldoPrecisao));
        }

        System.out.println("-------------------------------------------------");
        System.out.println(" TELEMETRIA DE MOVIMENTAÇÃO (FINAL):");
        
        double pesoS_Final = confiancaSurfing;
        double pesoM_Final = confiancaMRM;
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            pesoS_Final = 1.0;
            pesoM_Final = 0.0;
        } else if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
            pesoS_Final = 0.0; 
            pesoM_Final *= 2.5; 
        } else if (getOthers() > 1) { 
            pesoS_Final *= 2.5;
        }
        
        System.out.println(String.format(Locale.US, " Confiança Final  -> Surfing: %.2f | MRM: %.2f", pesoS_Final, pesoM_Final));
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
             System.out.println(" Override 1v1     : MRM Desativado (Foco TOTAL no Wave Surfing)");
        } else if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
             System.out.println(" Override 1v1     : Wave Surfing DESATIVADO - Modo Predador MRM Ativo");
        } else if (getOthers() > 1) {
             System.out.println(" Override Melee   : Peso do Surfing Aumentado (+150% Sobrevivência)");
        }
        
        boolean ehAvancadoConsole = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        System.out.println(" Buffer Surfing   : " + ((getOthers() <= 1 && ehAvancadoConsole) ? "1v1 Dedicado (c/ Inertia)" : "Melee Geral"));
        System.out.println(" Modo de Fuga     : " + (modoFuga ? "ATIVO no fim do round" : "Inativo"));
        System.out.println("=================================================");
    }

    public void onHitByBullet(HitByBulletEvent e) {
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.agressividade += 0.5; // Sofremos dano, então ele é extremamente perigoso
            inimigo.fatorAmeaca *= 1.1; 
        }
        
        // Punição: Qual motor falhou? Se MRM estava no controle, troca a confiança pro Surfing
        if (confiancaSurfing >= confiancaMRM) {
            confiancaSurfing = Math.max(0.1, confiancaSurfing - 0.3);
            confiancaMRM = Math.min(3.0, confiancaMRM + 0.15);
        } else {
            confiancaMRM = Math.max(0.1, confiancaMRM - 0.3);
            confiancaSurfing = Math.min(3.0, confiancaSurfing + 0.15);
        }
    }
    
    public void onBulletHit(BulletHitEvent e) {
        totalTirosAcertados++; 
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.saldoPrecisao += 15.0; // Pontua para classificar que nosso robô está acertando bem ele
        }
        
        if (rastreioBalasVG.containsKey(e.getBullet())) {
            acertosReaisVG[rastreioBalasVG.get(e.getBullet())]++;
        }
    }

    public void onBulletMissed(BulletMissedEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 5.0; // Punição por errarmos o tiro no alvo principal
        }
    }
    
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 1.0; 
        }
    }

    public void onRobotDeath(RobotDeathEvent event) {
        if (listaInimigos.containsKey(event.getName())) {
            listaInimigos.get(event.getName()).vivo = false;
        }
        if (alvo != null && alvo.nome != null && event.getName().equals(alvo.nome)) {
            alvo.vivo = false;
        }
    }
    
    public void onDeath(DeathEvent event) {
        if (alvo != null && alvo.nome != null) {
            derrotasSeguidas.put(alvo.nome, derrotasSeguidas.getOrDefault(alvo.nome, 0) + 1); // Salva na memória do nêmesis
        }
        exibirMetricas();
    }
    
    public void onWin(WinEvent event) {
        if (alvo != null && alvo.nome != null) {
            derrotasSeguidas.put(alvo.nome, 0); // Vingança concluída, reseta o Nêmesis
        }
        exibirMetricas();
        while (true) {
            coresBT7274(); // Dança da vitória do BT
            turnRadarRight(360);
        }
    }

    // =========================================================
    // PROTOCOLO DE SOBREVIVÊNCIA E FUGA
    // =========================================================
    public void verificarFuga() {
        if (meuRobo.energia < 25 || (getOthers() >= 4 && meuRobo.energia < 45)) {
            modoFuga = true; // Permite ao motor principal correr e largar o combate para salvar vida
        } else {
            modoFuga = false;
        }
    }

    // =========================================================
    // LÓGICA DE DISPARO (MELEE PREDITIVO COMUM)
    // Usada somente nos ticks do Melee onde não estamos focados 1v1
    // =========================================================
    public void disparar() {
        if (alvo != null && alvo.vivo) {
            double distancia = meuRobo.distance(alvo);
            double potencia = (distancia > 850 ? 0.1 : (distancia > 700 ? 0.5 : (distancia > 250 ? 2.0 : 3.0)));
            
            potencia = Math.min(meuRobo.energia / 4d, Math.min(alvo.energia / 3d, potencia));
            potencia = Utilitario.limitar(potencia, 0.1, 3.0);
            
            if (distancia < 150) {
                potencia = Math.min(3.0, meuRobo.energia / 12); 
            }
            
            long tempoAteAcerto;
            Point2D.Double mirarEm = new Point2D.Double();
            double direcao, deltaDirecao, velocidadeTiro;
            double preverX, preverY;
            
            preverX = alvo.getX();
            preverY = alvo.getY();
            direcao = alvo.direcao;
            deltaDirecao = direcao - alvo.ultimaDirecao;
            
            mirarEm.setLocation(preverX, preverY);
            tempoAteAcerto = 0;
            
            // Loop preditivo linear de tempo (calcula fisicamente em quanto tempo a bala e o inimigo chegam no local)
            if (alvo.ticksParado <= 5) {
                do {
                    preverX += Math.sin(direcao) * alvo.velocidade;
                    preverY += Math.cos(direcao) * alvo.velocidade;
                    direcao += deltaDirecao;
                    tempoAteAcerto++;
                    
                    Rectangle2D.Double areaDisparo = new Rectangle2D.Double(
                        MARGEM_PAREDE, MARGEM_PAREDE,
                        campoBatalha.width - MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE
                    );
                    
                    if (!areaDisparo.contains(preverX, preverY)) {
                        velocidadeTiro = mirarEm.distance(meuRobo) / tempoAteAcerto;
                        potencia = Utilitario.limitar((20 - velocidadeTiro) / 3.0, 0.1, 3.0);
                        break;
                    }
                    mirarEm.setLocation(preverX, preverY);
                    
                } while ((int) Math.round((mirarEm.distance(meuRobo) - MARGEM_PAREDE) / Rules.getBulletSpeed(potencia)) > tempoAteAcerto);
            }
            
            mirarEm.setLocation(
                Utilitario.limitar(preverX, 34, getBattleFieldWidth() - 34),
                Utilitario.limitar(preverY, 34, getBattleFieldHeight() - 34)
            );
            
            if ((getGunHeat() == 0.0) && (getGunTurnRemaining() == 0.0) && (potencia > 0.0) && (meuRobo.energia > 0.1)) {
                Bullet b = setFireBullet(potencia);
                if (b != null) totalTirosDisparados++;
            }
            
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                ((Math.PI / 2) - Math.atan2(mirarEm.y - meuRobo.getY(), mirarEm.x - meuRobo.getX())) - getGunHeadingRadians()
            ));
        }
    }

    // =========================================================
    // LÓGICA DE MOVIMENTO MINIMUM RISK (MRM - ZERO-ALLOCATION VECTOR MATH)
    // Este é o cérebro macro-direcional do Robô
    // =========================================================
    public void movimento() {
        // Recalcula a rota se atingirmos o destino ou ficarmos muito tempo inativos
        if (pontoAlvo.distance(meuRobo) < 15 || tempoInativo > 25) {
            tempoInativo = 0;
            
            // Gera mais pontos de análise em Melee (mais complexidade), e menos no 1v1
            int pontosAmostragem = (getOthers() > 1) ? 144 : 36;
            
            if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico) {
                pontosAmostragem = 12; // Robô burro não precisamos gastar muita CPU gerando matriz grande
            }
            
            atualizarListaPosicoes(pontosAmostragem); // Espalha centenas de pontinhos possíveis em volta do robô
            
            Point2D.Double pontoMenorRisco = null;
            double menorRisco = Double.MAX_VALUE;
            double melhorRiscoSurf = 0;
            double melhorRiscoMRM = 0;
            
            // Atribui uma "Nota de Risco" a cada ponto projetado. Queremos ir no de menor risco
            for (int i = 0; i < qtdPontosAtivos; i++) {
                Point2D.Double p = posicoesPossiveis.get(i);
                double riscoAtual = avaliarPonto(p);
                if (riscoAtual <= menorRisco || pontoMenorRisco == null) {
                    menorRisco = riscoAtual;
                    pontoMenorRisco = p;
                    melhorRiscoSurf = ultimoRiscoSurfingAvaliado;
                    melhorRiscoMRM = ultimoRiscoMRMAvaliado;
                }
            }
            // Fixa o alvo final
            pontoAlvo = pontoMenorRisco;
            riscoSurfingAlvoAtual = melhorRiscoSurf;
            riscoMRMAlvoAtual = melhorRiscoMRM;
            
        } else {
            tempoInativo++;
            double angulo = Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians();
            double direcao = 1;
            
            if (Math.cos(angulo) < 0) {
                angulo += Math.PI;
                direcao *= -1; // Vira de ré, se for mais rápido ir de ré para o alvo (poupa tempo de rotação)
            }
            
            double maxVel = 10 - (4 * Math.abs(getTurnRemainingRadians())); // Freia se tiver virando muito (Física de derrapagem)
            
            // Tremidinha caótica natural para despistar oponentes de mira linear enquanto dirige no 1v1
            if (getOthers() <= 1 && !ondasInimigas.isEmpty()) {
                double roletaJitter = Math.random();
                if (roletaJitter < 0.08) {
                    maxVel = 0.0; 
                } else if (roletaJitter < 0.15) {
                    maxVel = Math.random() * 6.0; 
                }
            }
            
            setMaxVelocity(maxVel);
            setAhead(meuRobo.distance(pontoAlvo) * direcao); // Pisa fundo pro ponto
            
            angulo = Utils.normalRelativeAngle(angulo);
            setTurnRightRadians(angulo);
        }
    }

    // Calcula de forma geométrica todos os pontos em forma de anéis estelares (Rings) num raio em volta
    public void atualizarListaPosicoes(int n) {
        int index = 0;
        double maxDist = 200.0;
        int rings = (n > 36) ? 4 : 1; 
        int anglesPerRing = n / rings;
        
        for (int r = 1; r <= rings; r++) {
            double dist = (maxDist / rings) * r;
            for (int a = 0; a < anglesPerRing; a++) {
                if (index >= posicoesPossiveis.size()) break; 
                
                double angle = (Math.PI * 2.0 * a) / anglesPerRing;
                double x = meuRobo.x + Math.sin(angle) * dist;
                double y = meuRobo.y + Math.cos(angle) * dist;
                
                // Trava esses pontos num box virtual da arena para não projetar pontos fora do mapa
                x = Utilitario.limitar(x, 75, campoBatalha.width - 75);
                y = Utilitario.limitar(y, 75, campoBatalha.height - 75);
                
                posicoesPossiveis.get(index).setLocation(x, y);
                index++;
            }
        }
        
        if (index < posicoesPossiveis.size()) {
            posicoesPossiveis.get(index).setLocation(meuRobo.x, meuRobo.y); // Inclui a posição atual (ficar parado)
            index++;
        }
        qtdPontosAtivos = index;
    }
    
    // =========================================================
    // NÚCLEO MATEMÁTICO: O AVALIADOR DE PONTOS (RISCO)
    // Calcula um campo de força gravitacional (Positivo e Negativo) baseado num ponto do mapa
    // =========================================================
    public double avaliarPonto(Point2D.Double p) {
        double riscoTotal = 0;
        double riscoSurfing = 0; // Parte oriunda de tentar desviar ativamente
        double riscoMRM = 0;     // Parte oriunda de macro-posicionamento (afastar de parede e inimigos)
        
        double px = p.x;
        double py = p.y;
        double cw = campoBatalha.width;
        double ch = campoBatalha.height;

        double distRoboPSq = (px - meuRobo.x) * (px - meuRobo.x) + (py - meuRobo.y) * (py - meuRobo.y);
        double distRoboP = Math.sqrt(distRoboPSq);

        int numOthers = getOthers();
        
        boolean modoShadowLight = (numOthers <= 1 && alvo != null && alvo.classificadoComoBasico);

        // Se inimigo é basico, o robô tende a procurar a órbita exata de 450 pixels em relação a ele (Órbita Segura)
        if (alvo != null && alvo.vivo && alvo.classificadoComoBasico) {
            double distParaAlvo = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            double desvioOrbita = Math.abs(distParaAlvo - 450.0); 
            riscoMRM += (desvioOrbita * 35.0); 
        }

        // INTEGRAÇÃO MRM COM WAVE SURFING: O Ponto também é pontuado pelas balas invisíveis cruzando ele
        if (habilitarSurfing && !ondasInimigas.isEmpty()) {
            double votoSurfing = 0;
            int ondasProcessadas = 0;
            
            for (OndaInimiga onda : ondasInimigas) {
                if (modoShadowLight && ondasProcessadas > 0) break;
                ondasProcessadas++;
                
                int binDoPonto = onda.obterBin(px, py);
                double riscoOnda = 0;
                
                int rangeAvaliacao = modoShadowLight ? 0 : 2;
                
                boolean isAvancado = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                int[] bufferSurf = (numOthers <= 1 && isAvancado) ? estatisticasSurfing1v1 : estatisticasSurfing;
                
                // Calcula o "Smearing": penaliza pesadamente o bin atual, mas também penaliza os vizinhos para não ficar tão perto do impacto
                for (int i = -rangeAvaliacao; i <= rangeAvaliacao; i++) {
                    int binAvaliado = (int) Utilitario.limitar(binDoPonto + i, 0, OndaInimiga.BINS_SURF - 1);
                    riscoOnda += bufferSurf[binAvaliado] * (1.0 / (Math.abs(i) + 1));
                }
                
                double distVoo = (getTime() - onda.tempoDisparo) * onda.velocidadeBala;
                
                double dxOnda = px - onda.origem.x;
                double dyOnda = py - onda.origem.y;
                double distRestante = Math.sqrt(dxOnda * dxOnda + dyOnda * dyOnda) - distVoo;
                
                if (distRestante > 0) {
                    votoSurfing += (riscoOnda * 8500.0) / Math.max(1, distRestante);
                }
            }
            riscoSurfing += votoSurfing;
            
            // Fator medo do Surfing: se estiver com muitos robos vivos, o risco do surf multiplica pra ser levado em conta
            if (numOthers > 4) {
                riscoSurfing *= 5.0; 
            }
        }
        
        // Estabilidade intrínseca de Movimento do MRM (Gosta de se mexer pra não ficar estático)
        double votoEstabilidade = Utilitario.aleatorioEntre(1.15, 2.42) / Math.max(1, distRoboPSq);
        riscoMRM += votoEstabilidade;
        
        double fatorMultidao = (6.85 * Math.max(0, numOthers - 1));
        
        // Anti-Gravidade Central: Em Melee, o meio da arena é punido pra não tomarmos crossfire (fogo cruzado)
        double cx = cw / 2.0;
        double cy = ch / 2.0;
        double distCenterSq = (px - cx)*(px - cx) + (py - cy)*(py - cy);
        double votoCentro = fatorMultidao / Math.max(1, distCenterSq);
        
        // Wall-Force: Ficar encostado nos 4 cantos da arena é altamente perigoso em Melee
        double pesoCanto = numOthers <= 5 ? (numOthers == 1 ? 0.32 : 0.58) : 1.15;
        if (modoFuga) {
            pesoCanto *= 0.12;
            votoCentro = 0; // Em desespero o canto da arena pode salvar
        }
        
        double distC1 = px*px + py*py;
        double distC2 = (cw-px)*(cw-px) + py*py;
        double distC3 = px*px + (ch-py)*(ch-py);
        double distC4 = (cw-px)*(cw-px) + (ch-py)*(ch-py);

        double votoCantos = pesoCanto / Math.max(1, distC1) +
                            pesoCanto / Math.max(1, distC2) +
                            pesoCanto / Math.max(1, distC3) +
                            pesoCanto / Math.max(1, distC4);
                            
        riscoMRM += votoCentro + votoCantos;
        
        boolean existeInimigoVivo = false;
        int botsBasicosVivos = 0; 
        
        double riscoShadow = 0;
        
        // --- LOOP PRINCIPAL DE CÁLCULO FÍSICO COM CADA INIMIGO ---
        for (Robo inimigo : listaInimigos.values()) {
            if (!inimigo.vivo) continue;
            existeInimigoVivo = true;
            
            if (inimigo.classificadoComoBasico) botsBasicosVivos++; 
            
            double dxInimigo = px - inimigo.x;
            double dyInimigo = py - inimigo.y;
            double distanciaSqInimigo = dxInimigo*dxInimigo + dyInimigo*dyInimigo;
            double distReal = Math.sqrt(distanciaSqInimigo);
            
            // Fator repulsivo universal: Se afaste dos inimigos
            double riscoBase = (1 / Math.max(1, distanciaSqInimigo));
            
            if (distReal < 300) riscoBase *= 10000.0; // Se colar, dá pânico e aplica risco gigante
            if (modoFuga) riscoBase *= 3.14; 
            
            riscoBase *= inimigo.fatorAmeaca;

            // --- PASSIVE BULLET SHADOWING --- (Apenas Melee)
            // Se um inimigo B estiver entre você e o inimigo A, usamos o B de escudo, zerando o perigo de A.
            if (numOthers > 1) {
                boolean isShadowed = false;
                for (Robo escudo : listaInimigos.values()) {
                    if (!escudo.vivo || escudo == inimigo) continue;
                    
                    double dxAB = escudo.x - inimigo.x;
                    double dyAB = escudo.y - inimigo.y;
                    
                    double t = (dxAB * dxInimigo + dyAB * dyInimigo) / Math.max(1.0, distanciaSqInimigo);
                    
                    if (t > 0 && t < 1) { // Existe intersecção da linha
                        double projX = inimigo.x + t * dxInimigo;
                        double projY = inimigo.y + t * dyInimigo;
                        
                        double distLinhaSq = (escudo.x - projX)*(escudo.x - projX) + (escudo.y - projY)*(escudo.y - projY);
                        
                        if (distLinhaSq < 1600.0) { // Tolerância de estar perfeitamente no raio de colisão do escudo humano
                            isShadowed = true;
                            break; 
                        }
                    }
                }
                if (isShadowed) {
                    riscoBase *= 0.1; // Se ele não pode nos acertar pois vai acertar o B, não temos medo
                }
            }
            
            // Modulador perpendicular Shadow: Posicionamentos perpendiculares ao inimigo dão menos risco
            double shadowCos = 0;
            if (distRoboP > 0 && distReal > 0) {
                double dotProductShadow = ((px - meuRobo.x) * dxInimigo + (py - meuRobo.y) * dyInimigo);
                shadowCos = Math.abs(dotProductShadow / (distRoboP * distReal));
            }
            riscoShadow += (Math.max(0.1, inimigo.energia) / Math.max(1, distanciaSqInimigo)) * (1.0 + shadowCos);
            
            // Fator Atração: Como predador, não podemos ir apenas para a borda. Mantemos distância média.
            if (!modoFuga && alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double baseDist = (numOthers > 1) ? 100.0 : 250.0; 
                double maxDist = (numOthers > 1) ? 400.0 : 700.0;
                
                // MODO PREDADOR BT-7274: Se for burro e for 1v1, a atração para encostar nele é ativada!
                if (numOthers <= 1 && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
                    baseDist = 100.0; 
                    maxDist = 350.0;  
                }
                
                // Distância ideal flexiona conforme a agressividade (Se ele atira mt, ficamos de longe)
                double distanciaIdeal = Utilitario.limitar(baseDist + (inimigo.agressividade * 25.0), 100.0, maxDist);
                
                double erroDistancia = Math.abs(distReal - distanciaIdeal);
                riscoMRM += (erroDistancia * 35.5); // Pune pontos que não estejam nesta órbita prefeita
                
                // Perto o suficiente para ser letal, reduz a dor de estar perto
                if (distanciaIdeal <= 350 && distReal < 300) {
                    riscoBase /= 10000.0; 
                }
                
                boolean isAmeacaAvancada = (inimigo.agressividade > 0.5 || inimigo.saldoPrecisao > 10.0 || inimigo.classificadoComoSurfer);
                
                // MODO PREDADOR: Atrai que nem um ímã (risco negativo) para cima do noob
                if (numOthers <= 1 && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer && distReal > 100) {
                    riscoMRM -= 45000.0 / Math.max(1, distReal); 
                }
                
                // Modo Caçador Elite: Se estamos longe da ameaça real, força atração
                double raioCaca = (numOthers > 1) ? 800 : 450; 
                if (isAmeacaAvancada && meuRobo.distance(inimigo) <= raioCaca) {
                    if (distReal > 100) { 
                        double forcaAtracao = (numOthers > 1) ? 50000.0 : 25000.0;
                        riscoMRM -= (forcaAtracao * (1.0 + inimigo.agressividade)) / Math.max(1, distReal);
                    }
                }
            }

            // Alinhamento perpendicular: Pontos laterais são mais seguros matematicamente para driblar predições básicas
            double alinhamentoPonto = 0;
            if (distRoboP > 0 && distReal > 0) {
                double dotProduct = ((px - meuRobo.x) * dxInimigo + (py - meuRobo.y) * dyInimigo);
                alinhamentoPonto = Math.abs(dotProduct / (distRoboP * distReal));
            }
            double multiplicadorRota = (1 + alinhamentoPonto);

            // Perpendicular Evasão cruzada em relação ao alvo primário
            double multiplicadorEvasao = 1.0;
            if (alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double distPAlvo = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
                if (distRoboP > 0 && distPAlvo > 0) {
                    double dx1 = px - meuRobo.x; double dy1 = py - meuRobo.y;
                    double dx2 = alvo.x - px;    double dy2 = alvo.y - py;
                    double cosRelativo = (dx1*dx2 + dy1*dy2) / (distRoboP * distPAlvo);
                    double sinRelativo = (dx1*dy2 - dy1*dx2) / (distRoboP * distPAlvo);
                    multiplicadorEvasao = 1.0 + ((1 - Math.abs(sinRelativo)) + Math.abs(cosRelativo)) / 2.0;
                }
            }
            
            // Consagra o risco com todos os multiplicadores (Rotacional + Fuga Perpendicular)
            riscoMRM += riscoBase * multiplicadorRota * multiplicadorEvasao;
        }
        
        riscoMRM += riscoShadow * 25000.0; 
        
        // --- BULLET SHADOWING (Evitar balas rastreadas) ---
        double votoTiros = 0;
        long tempoAtePonto = (long)(distRoboP / 8.0); 
        long tempoFuturo = getTime() + tempoAtePonto;
        
        int tirosProcessados = 0;
        for (int i = 0; i < tirosSuspeitos.size(); i++) {
            if (modoShadowLight && tirosProcessados > 0) break;
            tirosProcessados++;
            
            TiroInimigo t = tirosSuspeitos.get(i);
            // Calcula até onde a bala vai voar simulando o mesmo tempo que levariamos ao chegar lá
            double distTiroPercurso = (tempoFuturo - t.tempoDisparo) * t.velocidade;
            
            if(distTiroPercurso > 1500) {  // A bala explodiu/bateu ou está fora do mapa
                tirosSuspeitos.remove(i);
                i--;
                continue; 
            }
            
            double posTiroX = t.origem.x + Math.sin(t.angulo) * distTiroPercurso;
            double posTiroY = t.origem.y + Math.cos(t.angulo) * distTiroPercurso;
            
            double distSqTiro = (posTiroX - px)*(posTiroX - px) + (posTiroY - py)*(posTiroY - py);
            
            if(distSqTiro < 2500) { // Círculo de risco muito perto da bala
                votoTiros += 1050.5 / Math.max(1, distSqTiro);
            }
        }
        riscoMRM += votoTiros;
        
        // Custo inercial se não tem mais ninguem (Mover direto ou virar pra ir)
        if (!existeInimigoVivo) {
            riscoMRM += (1.1 + Math.abs(Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians()));
        }

        // Táticas de Sangria (Finalização): Se ele tá pra morrer e estamos muito bem de vida, vai pra cima finalizá-lo!
        if (alvo != null && alvo.vivo && alvo.energia < 15 && meuRobo.energia > (alvo.energia * 2.5) && numOthers <= 3) {
            double distPA = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            riscoMRM -= (125.0 / Math.max(1, distPA)); 
        }
        
        // Punição se tentarmos caçar um Surfer no corpo a corpo (Eles atiram bem, precisamos não estar muito perto)
        if (alvo != null && alvo.vivo && alvo.classificadoComoSurfer && existeInimigoVivo) {
            double distanciaParaSurfer = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            if (distanciaParaSurfer > 200) {
                riscoMRM -= (850.0 * alvo.fatorSurf) / Math.max(1, distanciaParaSurfer); 
            } else {
                riscoMRM += (150.0 / Math.max(1, distanciaParaSurfer)); 
            }
        }
        
        // Inércia Espelhada (Cópia da velocidade do Inimigo) para confundir rastreamento vetorial avançado inimigo
        if (alvo != null && alvo.vivo) {
            double direcaoRealInimigo = alvo.velocidade < 0 ? alvo.direcao + Math.PI : alvo.direcao;
            
            double vHeadX = Math.sin(direcaoRealInimigo);
            double vHeadY = Math.cos(direcaoRealInimigo);
            double cosInerciaAlvo = 0;
            if (distRoboP > 0) {
                cosInerciaAlvo = ((px - meuRobo.x) * vHeadX + (py - meuRobo.y) * vHeadY) / distRoboP;
            }
            double diferencaAngulo = Math.acos(Utilitario.limitar(cosInerciaAlvo, -1.0, 1.0));
            
            double votoImitacao = -18.5 * (1.0 - (diferencaAngulo / Math.PI)) * (Math.abs(alvo.velocidade) / 8.0);
            riscoMRM += votoImitacao;
        }
        
        // Impede de reverter a própria inércia muitas vezes pra não criar estol de motor (Robocode penaliza ficar parando)
        double vHeadX = Math.sin(getHeadingRadians());
        double vHeadY = Math.cos(getHeadingRadians());
        double cosInercia = 0;
        if (distRoboP > 0) {
            cosInercia = ((px - meuRobo.x) * vHeadX + (py - meuRobo.y) * vHeadY) / distRoboP;
        }
        
        // Fator caótico induzido por um seno temporal para causar erros nas IAs inimigas preditivas
        double fatorCaos = Math.sin((getTime() + meuRobo.x) / 85.5); 
        
        if (fatorCaos > 0.8 && cosInercia > 0.955) { 
            riscoMRM += 35.0; 
        } else if (fatorCaos < -0.8 && cosInercia < 0) { 
            riscoMRM += 35.0; 
        }
        
        // Grava pro HUD
        ultimoRiscoSurfingAvaliado = riscoSurfing;
        ultimoRiscoMRMAvaliado = riscoMRM;
        
        // Combinação e Overrides Finais dos Riscos Motores
        double pesoS = confiancaSurfing;
        double pesoM = confiancaMRM;
        
        if (numOthers <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            // Override 1v1 Surfer: Desliga o MRM 100% e confia puramente em surfar as ondas para não levar dano em hipótese alguma
            pesoS = 1.0; 
            pesoM = 0.0; 
        } else if (numOthers <= 1 && alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
            // Override Predador: Desliga o Surfing (pois não é necessário perder tempo desviando de tiro que não é inteligente) e esmaga com o MRM Agressivo 250%
            pesoS = 0.0; 
            pesoM *= 2.5; 
        } else if (numOthers > 1) { 
            // Override Melee: O Surfing ganha muito peso (250%) para tentar ser contabilizado no meio da imensidão de pontos gigantescos que o MRM cospe no meio da confusão
            pesoS *= 2.5; 
        }
        
        // Nota final deste Ponto no mapa. Na iteração, vai ver se ele é o menor de todos e focar nele se for.
        riscoTotal = (riscoSurfing * pesoS) + (riscoMRM * pesoM);
        
        return riscoTotal;
    }
}