package forge.game.keyword;

public class Firebending extends KeywordWithAmount {

    @Override
    protected String formatReminderText(String reminderText) {
        String fire;
        if (withX) {
            fire = "X {R}";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < amount; i++) {
                sb.append("{R}");
            }
            fire = sb.toString();
        }
        return String.format(reminderText, fire);
    }
}
