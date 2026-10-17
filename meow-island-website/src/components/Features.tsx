import { Bell, Droplets, Footprints, HeartPulse, Layers, Music2, Palette, Timer, ShieldAlert } from "lucide-react";
import FeatureCard from "./FeatureCard";
import MotionSection from "./MotionSection";
import { CustomizeDemo, GuardianDemo, HealthMiniDemo, IslandDemo, MusicDemo, NotificationDemo, ReminderDemo, WalkingDemo, WaterDemo } from "./Demos";

export default function Features() {
  return (
    <section id="features" className="mx-auto max-w-6xl px-5 py-24 sm:px-8">
      <MotionSection className="max-w-2xl">
        <h2 className="text-4xl font-extrabold tracking-[-0.03em] sm:text-5xl">A tiny cat with a lot of jobs.</h2>
        <p className="mt-4 text-lg leading-relaxed text-mist">
          Everything below is a mockup of what the Android app is built to do. Tap or focus any demo to step through it. Each card says what Android has to allow first.
        </p>
      </MotionSection>

      <div className="mt-12 grid gap-5 lg:grid-cols-6">
        <FeatureCard className="lg:col-span-3" icon={<Layers size={20} />} title="Floating Island"
          description="A compact pill that floats above everyday app content. By default it sits in the status-bar band, and you place it from the app's own Settings."
          note="Needs the Display over other apps permission. Android keeps system dialogs, the lock screen and secure windows above overlays, and overlays always sit below the status-bar icons."
          demo={<IslandDemo />} />
        <FeatureCard className="lg:col-span-3" delay={0.05} icon={<Music2 size={20} />} title="Music Controls"
          description="Title, artist and album art from what you're playing, with previous, play/pause and next. The disc spins while music plays and stops when it pauses."
          note="Needs Notification access, and the player must expose an Android media session. Not every app does. The track shown here is a placeholder."
          demo={<MusicDemo />} />
        <FeatureCard className="lg:col-span-2" icon={<Bell size={20} />} title="Notifications"
          description="A short preview of an incoming notification expands from the island, then tucks away again after a few seconds."
          note="Needs Notification access. Your real notifications stay in Android; this card is a mockup."
          demo={<NotificationDemo />} />
        <FeatureCard className="lg:col-span-2" delay={0.05} icon={<Timer size={20} />} title="Timers and Reminders"
          description="Schedule one-off, daily or weekly reminders, snooze them, or start a timer. Alerts show on the island as well as in a notification."
          note="Reminders use Android notifications and battery saving can delay them. This website demo does not schedule anything on your phone."
          demo={<ReminderDemo />} />
        <FeatureCard className="lg:col-span-2" delay={0.1} icon={<ShieldAlert size={20} />} title="Screen Time Guardian"
          description="Pick an app and set a limit. The cat stays calm while you're under it, turns grumpy once you're over, and cheers when you leave the app."
          note="Needs Usage access. Android can delay usage data, so reactions may lag. Off until you add an app."
          demo={<GuardianDemo />} />
        <FeatureCard className="lg:col-span-2" icon={<HeartPulse size={20} />} title="Optional Health Tracking"
          description="A step counter, daily goal and contribution heatmap. All of it stays off until you switch each part on."
          note="Needs the Physical activity permission and a step sensor in your phone. The numbers shown are sample values, not live readings."
          demo={<HealthMiniDemo />} />
        <FeatureCard className="lg:col-span-2" delay={0.05} icon={<Footprints size={20} />} title="Walking Progress"
          description="When walking is detected, the island shrinks to a slim progress bar toward your goal, then celebrates once on the day you reach it."
          note="Depends on your phone's step sensor, the permission, and Android's background limits. Detection can be delayed."
          demo={<WalkingDemo />} />
        <FeatureCard className="lg:col-span-2" delay={0.1} icon={<Droplets size={20} />} title="Water Reminders"
          description="A nudge appears on the island with Done and Snooze. You choose the start and end time, the interval, the days, and any quiet hours."
          note="Off by default and yours to turn off any time. Reminders use battery-friendly alarms and can arrive late. A reminder on your schedule, not medical advice."
          demo={<WaterDemo />} />
        <FeatureCard className="lg:col-span-6" wide icon={<Palette size={20} />} title="Customization"
          description="Size, colours, corner radius, animation speed and position all live in the app's Settings. Try the real controls below on a mock phone."
          note="Android decides which surfaces an overlay may appear above. The island can't cover protected system screens, and it moves only from Settings, not by dragging it on the phone."
          demo={<CustomizeDemo />} />
      </div>
    </section>
  );
}
