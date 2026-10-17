"use client";

import { motion, useReducedMotion } from "framer-motion";
import type { ReactNode } from "react";

/** One restrained reveal per block. With reduced motion the content simply appears. */
export default function MotionSection({
  children,
  className,
  delay = 0,
  as = "div",
}: {
  children: ReactNode;
  className?: string;
  delay?: number;
  as?: "div" | "li";
}) {
  const reduce = useReducedMotion();
  const M = as === "li" ? motion.li : motion.div;
  return (
    <M
      className={className}
      initial={reduce ? false : { opacity: 0, y: 18 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-60px" }}
      transition={{ duration: 0.6, delay, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </M>
  );
}
