package net.kodehawa.mantarobot;

import br.com.brjdevs.java.utils.Holder;
import com.mashape.unirest.http.Unirest;
import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.Async;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;

public class MantaroShard {
    private final Logger LOGGER;
    private JDA jda;
    private final int shardId;
    private final int totalShards;

    public MantaroShard(int shardId, int totalShards) throws RateLimitedException, LoginException, InterruptedException {
        this.shardId = shardId;
        this.totalShards = totalShards;
        LOGGER = LoggerFactory.getLogger("MantaroShard-" + shardId);
        restartJDA();
    }

    public void restartJDA() throws RateLimitedException, LoginException, InterruptedException {
        JDABuilder jdaBuilder = new JDABuilder(AccountType.BOT)
                .setToken(MantaroData.getConfig().get().token)
                .setAudioSendFactory(new NativeAudioSendFactory())
                .setAutoReconnect(true)
                .setGame(Game.of("Hold your seatbelts!"));
        if (totalShards > 1)
            jdaBuilder.useSharding(shardId, totalShards);
        if (jda != null) {
            prepareShutdown();
            jda.shutdown(false);
        }
        jda = jdaBuilder.buildBlocking();
    }

    public JDA getJDA() {
        return jda;
    }

    public int getId() {
        return shardId;
    }

    public int getTotalShards() {
        return totalShards;
    }

    public void prepareShutdown() {
        jda.getRegisteredListeners().forEach(listener -> jda.removeEventListener(listener));
    }

    public void updateServerCount() {
        Config config = MantaroData.getConfig().get();
        Holder<Integer> guildCount = new Holder<>(jda.getGuilds().size());

        String dbotsToken = config.dbotsToken;
        String carbonToken = config.carbonToken;
        String dbotsorgToken = config.dbotsorgToken;

        if (dbotsToken != null) {
            Async.startAsyncTask("List API update Thread", () -> {
                int newC = jda.getGuilds().size();
                if (newC != guildCount.get()) {
                    try {
                        guildCount.accept(newC);
                        //Unirest.post intensifies

                        try {
                            Unirest.post("https://bots.discord.pw/api/bots/" + jda.getSelfUser().getId() + "/stats")
                                    .header("Authorization", dbotsToken)
                                    .header("Content-Type", "application/json")
                                    .body(new JSONObject().put("server_count", newC).put("shard_id", getId()).put("shard_total", totalShards).toString())
                                    .asJsonAsync();
                            LOGGER.info("Successfully posted the botdata to discordbots");

                            LOGGER.info("Successfully posted the botdata to carbonitex.com: " +
                                    Unirest.post("https://www.carbonitex.net/discord/data/botdata.php")
                                            .field("key", carbonToken)
                                            .field("servercount", newC)
                                            .field("shardid", getId())
                                            .field("shardcount", totalShards)
                                            .asString().getBody());

                            Unirest.post("https://discordbots.org/api/bots/" + jda.getSelfUser().getId() + "/stats")
                                    .header("Authorization", dbotsorgToken)
                                    .header("Content-Type", "application/json")
                                    .body(new JSONObject().put("server_count", newC).put("shard_id", getId()).put("shard_total", totalShards).toString())
                                    .asJsonAsync();
                            LOGGER.info("Successfully posted the botdata to discordbots.org");
                        } catch (Exception e) {
                            LOGGER.error("An error occured while posting the botdata to discord lists (DBots/Carbonitex/DBots.org) - Shard " + getId(), e);
                        }

                        LOGGER.info("Updated discord lists Guild Count: " + newC + " guilds");
                    } catch (Exception e) {
                        LOGGER.error("An error occured while posting the botdata to discord lists (DBots/Carbonitex/DBots.org)", e);
                    }
                }
            }, 1800);
        }
    }

    @Override
    public String toString() {
        return "Shard [" + getId() + "/" + totalShards +" ]";
    }
}