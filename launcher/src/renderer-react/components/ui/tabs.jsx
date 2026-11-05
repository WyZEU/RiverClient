import React from "react";
import * as TabsPrimitive from "@radix-ui/react-tabs";
import { cn } from "../../lib/utils";
import { useModern } from "../../lib/ui-style";

export const Tabs = TabsPrimitive.Root;

export const TabsList = React.forwardRef(({ className, ...props }, ref) => {
  const modern = useModern();
  return (
    <TabsPrimitive.List
      ref={ref}
      className={cn(
        "inline-flex items-center justify-center gap-1 p-1",
        modern ? "h-10 rounded-xl border border-border/60 bg-card" : "h-9 rounded-md bg-secondary",
        className
      )}
      {...props}
    />
  );
});
TabsList.displayName = "TabsList";

export const TabsTrigger = React.forwardRef(({ className, ...props }, ref) => {
  const modern = useModern();
  return (
    <TabsPrimitive.Trigger
      ref={ref}
      className={cn(
        "inline-flex items-center justify-center whitespace-nowrap text-muted-foreground hover:text-foreground focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50",
        modern
          ? "h-full rounded-lg px-3.5 text-[13px] font-semibold transition-colors focus-visible:ring-2 focus-visible:ring-ring data-[state=active]:bg-primary/15 data-[state=active]:text-foreground"
          : "rounded-sm px-3 py-1 text-xs font-medium transition-all data-[state=active]:bg-accent data-[state=active]:text-foreground",
        className
      )}
      {...props}
    />
  );
});
TabsTrigger.displayName = "TabsTrigger";

export const TabsContent = React.forwardRef(({ className, ...props }, ref) => (
  <TabsPrimitive.Content ref={ref} className={cn("mt-4 focus-visible:outline-none", className)} {...props} />
));
TabsContent.displayName = "TabsContent";
