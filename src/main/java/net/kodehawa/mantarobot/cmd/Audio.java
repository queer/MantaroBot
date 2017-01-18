package net.kodehawa.mantarobot.cmd;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import net.dv8tion.jda.core.managers.AudioManager;
import net.kodehawa.mantarobot.audio.MusicManager;
import net.kodehawa.mantarobot.cmd.guild.Parameters;
import net.kodehawa.mantarobot.core.Mantaro;
import net.kodehawa.mantarobot.log.Log;
import net.kodehawa.mantarobot.log.Type;
import net.kodehawa.mantarobot.module.Callback;
import net.kodehawa.mantarobot.module.Category;
import net.kodehawa.mantarobot.module.CommandType;
import net.kodehawa.mantarobot.module.Module;

import java.awt.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Audio extends Module {

    private final AudioPlayerManager playerManager;
    private final Map<Long, MusicManager> musicManagers;
    private Member _member;
    private MessageReceivedEvent _eventTemp;
    private Timer timer = new Timer();

    public Audio(){
        super.setCategory(Category.AUDIO);
        this.musicManagers = new HashMap<>();
        this.playerManager = new DefaultAudioPlayerManager();
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
        this.registerCommands();
    }

    @Override
    public void registerCommands(){
        super.register("play", "Plays a song in the music voice channel.", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                _eventTemp = event;
                _member = event.getMember();
                try {
                    new URL(content);
                }
                catch(Exception e) {
                    content = "ytsearch: " + content;
                }

                loadAndPlay(event.getGuild(), event.getTextChannel(), content);
            }

            @Override
            public String help() {
                return "Plays a song in the music voice channel.\n"
                        + "Usage:\n"
                        + "~>play [youtubesongurl] (Can be a song, a playlist or a search)";
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER;
            }
        });

        super.register("skip", "Stops the track and continues to the next one, if there is one.", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                skipTrack(event.getTextChannel(), event);
            }

            @Override
            public String help() {
                return "Stops the track and continues to the next one, if there is one.\n"
                        + "Usage:\n"
                        + "~>skip";
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER;
            }
        });

        super.register("shuffle", "Shuffles the current playlist", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                MusicManager musicManager = musicManagers.get(Long.parseLong(event.getGuild().getId()));
                shuffle(musicManager);
                event.getChannel().sendMessage(":mega: Randomized current queue order.").queue();
            }

            @Override
            public String help() {
                return null;
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER;
            }
        });

        super.register("stop", "Clears queue and leaves the voice channel.", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                MusicManager musicManager = musicManagers.get(Long.parseLong(event.getGuild().getId()));
                clearQueue(musicManager, event);
                closeConnection(musicManager, event.getGuild().getAudioManager(), event.getTextChannel());
            }

            @Override
            public String help() {
                return "Clears the queue and leaves the voice channel.\n"
                        + "Usage:\n"
                        + "~>stop";
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER;
            }
        });

        super.register("queue", "Returns the current track list playing on the server.", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                MusicManager musicManager = musicManagers.get(Long.parseLong(event.getGuild().getId()));
                if(content.isEmpty()){
                    event.getChannel().sendMessage(embedQueueList(event.getGuild(), musicManager)).queue();
                } else if(content.startsWith("clear")){
                    clearQueue(musicManager, event);
                }
            }

            @Override
            public String help() {
                return "Returns the current queue playing on the server or clears it.\n"
                        + "Usage:\n"
                        + "~>queue"
                        + "~>queue clear";
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER;
            }
        });

        super.register("removetrack", "Removes the specified track from the queue.", new Callback() {
            @Override
            public void onCommand(String[] args, String content, MessageReceivedEvent event) {
                MusicManager musicManager = musicManagers.get(Long.parseLong(event.getGuild().getId()));
                int n = 0;
                for(AudioTrack audioTrack : musicManager.getScheduler().getQueue()){
                    if(n == Integer.parseInt(content) - 1){
                        event.getChannel().sendMessage("Removed track: " + audioTrack.getInfo().title).queue();
                        musicManager.getScheduler().getQueue().remove(audioTrack);
                        break;
                    }
                    n++;
                }
            }

            @Override
            public String help() {
                return "Removes the specified track from the queue.\n"
                        + "Usage:\n"
                        + "~>removetrack [tracknumber] (as specified on the ~>queue command)";
            }

            @Override
            public CommandType commandType() {
                return CommandType.USER ;
            }
        });
    }

    private synchronized MusicManager getGuildAudioPlayer(Guild guild) {
        long guildId = Long.parseLong(guild.getId());
        MusicManager musicManager = musicManagers.get(guildId);
        if (musicManager == null) {
            musicManager = new MusicManager(playerManager);
            musicManagers.put(guildId, musicManager);
        }

        guild.getAudioManager().setSendingHandler(musicManager.getSendHandler());

        return musicManager;
    }

    private void loadAndPlay(final Guild guild, final TextChannel channel, final String trackUrl) {
        MusicManager musicManager = getGuildAudioPlayer(channel.getGuild());
        playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                loadTrack(guild, channel, musicManager, track, false);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                int i = 0;
                if(!playlist.isSearchResult()){
                    for(AudioTrack audioTrack : playlist.getTracks()){
                        if(i <= 60){
                            loadTrack(guild, channel, musicManager, audioTrack, true);
                        } else {
                            break;
                        }
                        i++;
                    }
                    long templength = 0;
                    for(AudioTrack temp : playlist.getTracks()){
                        templength = templength
                                + temp.getInfo().length;
                    }
                    channel.sendMessage("Added **" + playlist.getTracks().size()
                            + " songs** to queue on playlist: **"
                            + playlist.getName() + "**" + " *("
                            + getDurationMinutes(templength) + ")*"
                    ).queue();
                } else {
                    String[] args = {"1", "2", "3", "4"};
                    List<String> content = new ArrayList<>();
                    int i1 = 0;
                    for(AudioTrack at : playlist.getTracks()){
                        if(i1 <= 3){
                            content.add(at.getInfo().title + " **(" + getDurationMinutes(at.getInfo().length) + ")**");
                        }
                        i1++;
                    }
                    MusicArgListener listener1 = new MusicArgListener(playlist, guild, musicManager, content, _eventTemp.getAuthor().getId(), args, _eventTemp);
                    Mantaro.instance().getSelf().addEventListener(listener1);
                    TimerTask ts = new TimerTask() {
                        @Override
                        public void run() {
                            Mantaro.instance().getSelf().removeEventListener(listener1);
                            channel.sendMessage(":heavy_multiplication_x: Timeout: No reply in 10 seconds").queue();
                        }
                    };

                    timer.schedule(ts, 10000);
                }
            }

            @Override
            public void noMatches() {
                channel.sendMessage(":heavy_multiplication_x: Nothing found on " + trackUrl).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if(!exception.severity.equals(FriendlyException.Severity.FAULT)){
                    Log.instance().print("Couldn't play music", this.getClass(), Type.WARNING, exception);
                    channel.sendMessage(":heavy_multiplication_x: Couldn't play music: " + exception.getMessage() + " SEVERITY: " + exception.severity).queue();
                }
            }
        });
    }

    private void play(Guild guild, MusicManager musicManager, AudioTrack track, Member member) {
        connectToUserVoiceChannel(guild.getAudioManager(), member);
        musicManager.getScheduler().queue(track);
    }

    private void play(String cid, Guild guild, MusicManager musicManager, AudioTrack track) {
        connectToNamedVoiceChannel(cid, guild.getAudioManager());
        musicManager.getScheduler().queue(track);
    }

    private void shuffle(MusicManager musicManager){
        java.util.List<AudioTrack> temp = new ArrayList<>();
        BlockingQueue<AudioTrack> bq = musicManager.getScheduler().getQueue();
        if(!bq.isEmpty()){
            bq.drainTo(temp);
        }
        bq.clear();

        java.util.Random rand = new java.util.Random();
        Collections.shuffle(temp, new java.util.Random(rand.nextInt(18975545)));

        for(AudioTrack track : temp){
            bq.add(track);
        }

        temp.clear();
    }

    private void skipTrack(TextChannel channel, MessageReceivedEvent event) {
        MusicManager musicManager = getGuildAudioPlayer(channel.getGuild());
        if(nextTrackAvailable(musicManager)){
            String nextSongName = "";
            try{
                nextSongName = musicManager.getScheduler().getQueue().take().getInfo().title;
            } catch (Exception ignored){}
            musicManager.getScheduler().nextTrack();
            channel.sendMessage(":mega: Skipped to next track. (**" + nextSongName + "**)").queue();
        } else {
            channel.sendMessage("No tracks next. Disconnecting...").queue();
            closeConnection(musicManager, event.getGuild().getAudioManager(), channel);
        }
    }

    private static void connectToUserVoiceChannel(AudioManager audioManager, Member member) {
        if(member.getVoiceState().getChannel() != null)
        {
            if (!audioManager.isConnected() && !audioManager.isAttemptingToConnect()) {
                audioManager.openAudioConnection(member.getVoiceState().getChannel());
            }
        }
    }

    private static void connectToNamedVoiceChannel(String voiceId, AudioManager audioManager){
        if (!audioManager.isConnected() && !audioManager.isAttemptingToConnect()) {
            for (VoiceChannel voiceChannel : audioManager.getGuild().getVoiceChannels()) {
                if(voiceChannel.getId().equals(voiceId)){
                    audioManager.openAudioConnection(voiceChannel);
                    break;
                }
            }
        }
    }

    private void closeConnection(MusicManager musicManager, AudioManager audioManager, TextChannel channel) {
        musicManager.getScheduler().getQueue().clear();
        audioManager.closeAudioConnection();
        channel.sendMessage(":mega: Closed audio connection.").queue();
    }

    private boolean nextTrackAvailable(MusicManager musicManager){
        if(musicManager.getScheduler().getQueueSize() > 0){
            return true;
        }
        return false;
    }

    private MessageEmbed embedQueueList(Guild guild, MusicManager musicManager) {
        String toSend = musicManager.getScheduler().getQueueList();
        String[] lines = toSend.split("\r\n|\r|\n");
        List<String> lines2 = new ArrayList<>(Arrays.asList(lines));
        StringBuilder stringBuilder = new StringBuilder();
        int temp = 0;
        for(int i = 0; i < lines2.size(); i++){
            temp++;
            if(i <= 14){
                stringBuilder.append
                        (lines2.get(i))
                        .append("\n");
            }
            else {
                lines2.remove(i);
            }
        }

        if(temp > 15){
            stringBuilder.append("\nShowing only first **15** results.");
        }

        long templength = 0;
        for(AudioTrack temp1 : musicManager.getScheduler().getQueue()){
            templength = templength
                    + temp1.getInfo().length;
        }

        EmbedBuilder builder = new EmbedBuilder();
        builder.setAuthor("Queue for server " + guild.getName(), null, guild.getIconUrl());
        builder.setColor(Color.CYAN);
        if(!toSend.isEmpty()){
            builder.setDescription(stringBuilder.toString());
            builder.addField("Queue runtime", getDurationMinutes(templength), true);
            builder.addField("Total queue size", String.valueOf(musicManager.getScheduler().getQueue().size()), true);
        } else {
            builder.setDescription("Nothing here, just dust.");
        }

        return builder.build();
    }

    private void loadTrack(Guild guild, TextChannel channel, MusicManager musicManager, AudioTrack track, boolean isPlaylist){
        try{
            if(track.getDuration() > 600000){
                channel.sendMessage(
                        ":heavy_multiplication_x:"
                                + " Track added is longer than 10 minutes (>600000ms). Cannot add "
                                + track.getInfo().title
                                + " (Track length: " + getDurationMinutes(track) + ")"
                ).queue();
                return;
            }

            if(Parameters.getMusicVChannelForServer(guild.getId()).isEmpty()){
                play(channel.getGuild(), musicManager, track, _member);

                if(!isPlaylist)
                    channel.sendMessage(
                            ":mega: Added to queue **" + track.getInfo().title + "**"
                            + " **!(" + getDurationMinutes(track) + ")**"
                    ).queue();
            } else {
                play(Parameters.getMusicVChannelForServer(
                        guild.getId()), channel.getGuild(), musicManager, track);

                if(!isPlaylist)
                    channel.sendMessage(
                            ":mega: Added to queue **" + track.getInfo().title + "**"
                                    + " **(" + getDurationMinutes(track) + ")**"
                    ).queue();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearQueue(MusicManager musicManager, MessageReceivedEvent event){
        int TEMP_QUEUE_LENGHT = musicManager.getScheduler().getQueue().size();
        for(AudioTrack audioTrack : musicManager.getScheduler().getQueue()){
            musicManager.getScheduler().getQueue().remove(audioTrack);
        }
        event.getChannel().sendMessage("Removed **" + TEMP_QUEUE_LENGHT + " songs** from queue.").queue();
        skipTrack(event.getTextChannel(), event);
    }

    private String getDurationMinutes(AudioTrack track){
        long TRACK_LENGHT = track.getInfo().length;
        return String.format("%d:%d minutes",
                TimeUnit.MILLISECONDS.toMinutes(TRACK_LENGHT),
                TimeUnit.MILLISECONDS.toSeconds(TRACK_LENGHT) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(TRACK_LENGHT))
        );
    }

    private String getDurationMinutes(long length){
        return String.format("%d:%d minutes",
                TimeUnit.MILLISECONDS.toMinutes(length),
                TimeUnit.MILLISECONDS.toSeconds(length) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(length))
        );
    }

    private class MusicArgListener extends ListenerAdapter {
        private String _userId;
        String[] _args;
        MessageReceivedEvent _evt;
        List _list;
        Guild _guild;
        MusicManager _musicManager;
        AudioPlaylist _audioPlaylist;

        MusicArgListener(AudioPlaylist audioPlaylist, Guild guild, MusicManager musicManager, List<String> list, String id, String[] args, MessageReceivedEvent evt){
            _guild = guild;
            _musicManager = musicManager;
            _audioPlaylist = audioPlaylist;
            _userId = id;
            _args = args;
            _evt = evt;
            _list = list;

            StringBuilder sb = new StringBuilder();
            int n = 0;
            for(String s : list) {
                n++;
                sb.append("[").append(n).append("] ").append(s).append("\n");
            }
            EmbedBuilder embedBuilder = new EmbedBuilder();
            embedBuilder.setColor(Color.CYAN);
            embedBuilder.setTitle("Song selection. Type the song number to continue.");
            embedBuilder.setDescription(sb.toString());
            embedBuilder.setFooter("This timeouts in 10 seconds.", null);

            _evt.getChannel().sendMessage(embedBuilder.build()).queue();
        }

        @Override
        public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
            if(event.getAuthor().getId().equals(_userId)) {
                for (String s : _args) {
                    if (event.getMessage().getContent().startsWith(s)) {
                        loadTrack(_guild, event.getChannel(), _musicManager, _audioPlaylist.getTracks().get(Integer.parseInt(s) - 1), false);
                        Mantaro.instance().getSelf().removeEventListener(this);
                        timer.cancel();
                        break;
                    }
                }
            }
        }
    }
}