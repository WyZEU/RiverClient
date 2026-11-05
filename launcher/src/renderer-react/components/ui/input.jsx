import React from "react";
import { cn } from "../../lib/utils";
import { useModern } from "../../lib/ui-style";

export const Input = React.forwardRef(({ className, type = "text", ...props }, ref) => {
  const modern = useModern();
  return (
    <input
      ref={ref}
      type={type}
      className={cn(
        "flex w-full rounded-md border bg-background px-3 py-1 text-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        modern ? "h-10 border-border/70" : "h-9 border-input",
        className
      )}
      {...props}
    />
  );
});
Input.displayName = "Input";
