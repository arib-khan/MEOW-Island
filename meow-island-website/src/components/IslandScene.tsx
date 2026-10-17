"use client";

import { useMemo, useRef } from "react";
import { Canvas, useFrame } from "@react-three/fiber";
import { Environment, Lightformer, RoundedBox } from "@react-three/drei";
import * as THREE from "three";

const PURPLE = "#8b5cf6";
const LILAC = "#b79cff";
const PINK = "#ff8fc0";
const SHELL = "#1b1433";

function Ear({ side }: { side: 1 | -1 }) {
  return (
    <group position={[side * 1.05, 0.88, 0]} rotation={[0, 0, side * -0.22]}>
      {/* three radial segments = a triangular prism, reads as a cat ear */}
      <mesh rotation={[0, Math.PI / 2 + (side === 1 ? 0 : Math.PI), 0]}>
        <coneGeometry args={[0.46, 0.8, 3]} />
        <meshStandardMaterial color={SHELL} metalness={0.4} roughness={0.35} emissive={PURPLE} emissiveIntensity={0.12} />
      </mesh>
      <mesh position={[0, -0.06, 0.2]} rotation={[0, Math.PI / 2 + (side === 1 ? 0 : Math.PI), 0]} scale={[0.5, 0.62, 0.5]}>
        <coneGeometry args={[0.46, 0.8, 3]} />
        <meshStandardMaterial color={PINK} emissive={PINK} emissiveIntensity={0.5} transparent opacity={0.55} />
      </mesh>
    </group>
  );
}

function Eye({ x }: { x: number }) {
  return (
    <group position={[x, 0.08, 0.6]}>
      <mesh scale={[1, 1.3, 0.45]}>
        <sphereGeometry args={[0.17, 32, 32]} />
        <meshStandardMaterial color="#a77bff" emissive={PURPLE} emissiveIntensity={2.6} toneMapped={false} />
      </mesh>
      <mesh position={[0, 0, 0.05]} scale={[0.34, 1.1, 0.4]}>
        <sphereGeometry args={[0.12, 16, 16]} />
        <meshBasicMaterial color="#0b0714" />
      </mesh>
    </group>
  );
}

function Whiskers() {
  const lines = useMemo(() => [[-1, 0.1], [-1, -0.08], [1, 0.1], [1, -0.08]] as const, []);
  return (
    <group position={[0, -0.22, 0.58]}>
      {lines.map(([s, y], i) => (
        <mesh key={i} position={[s * 0.95, y, 0]} rotation={[0, 0, s * (y > 0 ? -0.12 : 0.1) + Math.PI / 2]}>
          <cylinderGeometry args={[0.008, 0.008, 0.62, 6]} />
          <meshBasicMaterial color={LILAC} transparent opacity={0.6} />
        </mesh>
      ))}
    </group>
  );
}

function Particles({ reduced }: { reduced: boolean }) {
  const ref = useRef<THREE.Points>(null);
  const positions = useMemo(() => {
    const n = 55;
    const a = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const r = 2.6 + Math.random() * 1.9;
      const th = Math.random() * Math.PI * 2;
      a[i * 3] = Math.cos(th) * r;
      a[i * 3 + 1] = (Math.random() - 0.5) * 3.4;
      a[i * 3 + 2] = Math.sin(th) * r * 0.55 - 0.6;
    }
    return a;
  }, []);
  useFrame((_, dt) => { if (ref.current && !reduced) ref.current.rotation.y += dt * 0.04; });
  return (
    <points ref={ref}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      <pointsMaterial size={0.035} color={LILAC} transparent opacity={0.7} sizeAttenuation depthWrite={false} />
    </points>
  );
}

function GroundGlow() {
  const tex = useMemo(() => {
    const c = document.createElement("canvas");
    c.width = c.height = 128;
    const g = c.getContext("2d")!;
    const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    grad.addColorStop(0, "rgba(139,92,246,0.5)");
    grad.addColorStop(0.4, "rgba(226,85,214,0.12)");
    grad.addColorStop(0.75, "rgba(0,0,0,0)");
    g.fillStyle = grad;
    g.fillRect(0, 0, 128, 128);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }, []);
  return (
    <mesh position={[0, -1.3, 0]} rotation={[-Math.PI / 2, 0, 0]}>
      <planeGeometry args={[4.4, 4.4]} />
      <meshBasicMaterial map={tex} transparent depthWrite={false} blending={THREE.AdditiveBlending} />
    </mesh>
  );
}

function Island({ reduced }: { reduced: boolean }) {
  const group = useRef<THREE.Group>(null);
  const eyes = useRef<THREE.Group>(null);
  useFrame((state, dt) => {
    const g = group.current;
    if (!g || reduced) return;
    const t = state.clock.elapsedTime;
    const { x, y } = state.pointer; // pointer or touch-drag, normalised -1..1
    g.rotation.y = THREE.MathUtils.damp(g.rotation.y, x * 0.55 + Math.sin(t * 0.45) * 0.14, 3.2, dt);
    g.rotation.x = THREE.MathUtils.damp(g.rotation.x, -y * 0.22, 3.2, dt);
    g.position.y = Math.sin(t * 1.1) * 0.12;
    g.scale.setScalar(1 + Math.sin(t * 1.7) * 0.012);
    if (eyes.current) {
      const blink = Math.sin(t * 1.3) > 0.985 ? 0.12 : 1;
      eyes.current.scale.y = THREE.MathUtils.damp(eyes.current.scale.y, blink, 25, dt);
    }
  });
  return (
    <group ref={group}>
      {/* outer rim glow */}
      <RoundedBox args={[3.62, 1.32, 1.28]} radius={0.62} smoothness={6}>
        <meshBasicMaterial color={PURPLE} transparent opacity={0.16} side={THREE.BackSide} blending={THREE.AdditiveBlending} depthWrite={false} />
      </RoundedBox>
      <RoundedBox args={[3.4, 1.1, 1.1]} radius={0.52} smoothness={8}>
        <meshStandardMaterial color={SHELL} metalness={0.45} roughness={0.42} envMapIntensity={1} />
      </RoundedBox>
      <Ear side={-1} />
      <Ear side={1} />
      <group ref={eyes}>
        <Eye x={-0.62} />
        <Eye x={0.62} />
      </group>
      <mesh position={[0, -0.2, 0.56]} rotation={[0, 0, Math.PI]}>
        <coneGeometry args={[0.07, 0.08, 3]} />
        <meshStandardMaterial color={PINK} emissive={PINK} emissiveIntensity={0.7} />
      </mesh>
      <Whiskers />
    </group>
  );
}

export default function IslandScene({ reduced, active }: { reduced: boolean; active: boolean }) {
  return (
    <Canvas
      frameloop={active && !reduced ? "always" : "demand"}
      dpr={[1, 1.75]}
      camera={{ position: [0, 0.2, 6.2], fov: 38 }}
      gl={{ antialias: true, alpha: true, powerPreference: "high-performance" }}
      style={{ touchAction: "pan-y" }}
      aria-hidden="true"
    >
      <ambientLight intensity={0.35} />
      <pointLight position={[-3.2, 1.4, 2.4]} intensity={26} color={PURPLE} distance={12} decay={2} />
      <pointLight position={[3.4, -1.2, 2.2]} intensity={20} color={PINK} distance={12} decay={2} />
      <pointLight position={[0, 2.6, -2.5]} intensity={26} color={LILAC} distance={12} decay={2} />
      <Environment resolution={64} frames={1}>
        <Lightformer form="rect" intensity={2.2} color={LILAC} position={[-4, 2, 3]} scale={[4, 1.4, 1]} />
        <Lightformer form="rect" intensity={1.6} color={PINK} position={[4, -1, 2]} scale={[3, 1, 1]} />
      </Environment>
      <Island reduced={reduced} />
      <Particles reduced={reduced} />
      <GroundGlow />
    </Canvas>
  );
}
