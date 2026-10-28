package com.automaforge.android;

final class AiPrompts {
    private AiPrompts() {}

    static String rules(String gameName) {
        return "Manual de \"" + gameName + "\". Extraia apenas regras explícitas para configuração, turnos e ações " +
            "de um adversário artificial. Seja conciso em todos os campos. Não invente; registre dúvidas. " +
            "Sem modo solo, marque ações como hipóteses de emulação. Leia os arquivos na ordem enviada. Português.";
    }

    static String board(String rules, String state) {
        return "Descreva apenas o visível na foto; não infira elementos ocultos nem altere o estado. " +
            "Liste observações, mudanças possíveis e dúvidas em frases curtas. Português. Regras: " +
            GameStore.clip(rules, 16000) + " Estado: " + state;
    }

    static String privateCard(String rules) {
        return "Leia a carta privada da automa. Transcreva apenas o texto legível, sem inferir nem inventar. " +
            "Mantenha a leitura privada. Dúvidas breves. Português. Regras: " +
            GameStore.clip(rules, 10000);
    }

    static String proposal(String context) {
        return "Escolha uma ação usando exatamente seu nome na lista; não invente movimentos ou condições. " +
            "Use cartas privadas só para decidir; nunca revele seu conteúdo. Português. Seja breve: reason em uma frase, " +
            "spoken em uma frase natural na primeira pessoa e player_steps só com passos necessários. " +
            "Se o estado for incerto, use confiança baixa e não proponha mudanças. A jogada será aprovada por uma pessoa. Contexto: " +
            GameStore.clip(context, 30000);
    }
}
