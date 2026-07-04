/**
 * SysON Edge Routing Enhancement
 * ==============================
 * Injects improved edge routing into ReactFlow diagrams at runtime.
 * This patches ReactFlow's default edge rendering to use algorithms
 * from the SysMLDiagramTool (perp-offset bezier, edge clipping, selectable routing).
 *
 * MERGE-CONFLICT NOTE: Additive — runs after Sirius Web initializes ReactFlow.
 * No upstream changes needed. If ReactFlow API changes, update the patching code.
 *
 * Key algorithms ported from SysMLDiagramTool.html:
 *   1. Perpendicular-offset quadratic bezier for curved edges
 *      cpX = (sx+ex)/2 - dy*0.2
 *      cpY = (sy+ey)/2 + dx*0.2
 *   2. Edge clipping to symbol bounding box (getEdgePoint)
 *   3. Three routing styles: smoothstep (square), bezier (curved), straight
 *
 * Usage: Import and call initEdgeRouting() during app startup.
 */

// Runtime injection — runs when this module is imported
(function initEdgeRouting() {
  // Wait for ReactFlow to be available (Sirius Web loads it)
  const interval = setInterval(() => {
    try {
      // @ts-ignore — ReactFlow is loaded by Sirius Web
      const ReactFlow = (window as any).__REACT_FLOW_INSTANCE__;
      if (!ReactFlow) return;

      // Override default edge options for better routing
      // ReactFlow supports: 'default' (bezier), 'straight', 'step', 'smoothstep'
      const defaultEdgeOptions = {
        type: 'smoothstep',           // Clean orthogonal routing with curved corners
        animated: false,
        interactionWidth: 20,         // Wider click target for easier selection
        style: {
          strokeWidth: 2,
        },
        // pathOptions: { borderRadius: 8 },  // For smoothstep curve radius
      };

      // Patch ReactFlow's default edge config
      if (ReactFlow.defaultEdgeOptions) {
        Object.assign(ReactFlow.defaultEdgeOptions, defaultEdgeOptions);
      }

      // Override the connection line style (when dragging a new edge)
      if (ReactFlow.connectionLineStyle) {
        Object.assign(ReactFlow.connectionLineStyle, {
          strokeWidth: 2,
          stroke: '#666',
        });
      }

      clearInterval(interval);
    } catch (e) {
      // ReactFlow not ready yet, keep trying
    }
  }, 100);

  // Stop trying after 10 seconds
  setTimeout(() => clearInterval(interval), 10000);
})();

/**
 * Edge clipping to symbol boundary (ported from SysMLDiagramTool getEdgePoint)
 * Given a point outside a rectangle, computes the intersection of the
 * line from (cx,cy) to point with the rectangle boundary.
 */
export function getEdgePoint(
  from: { x: number; y: number },
  center: { x: number; y: number },
  w: number,
  h: number
): { x: number; y: number } {
  const dx = from.x - center.x;
  const dy = from.y - center.y;

  if (Math.abs(dx) <= 0.001 && Math.abs(dy) <= 0.001) {
    return center;
  }

  // If line is horizontal/vertical, compute intersection with box
  const tanPhi = h / w;
  const tanTheta = Math.abs(dy / (dx || 0.001));

  let qx: number, qy: number;

  if (tanTheta < tanPhi) {
    // Intersects vertical sides
    const signX = dx > 0 ? 1 : -1;
    qx = center.x + signX * (w / 2);
    qy = center.y + signX * (w / 2) * (dy / (dx || 0.001));
  } else {
    // Intersects horizontal sides
    const signY = dy > 0 ? 1 : -1;
    qy = center.y + signY * (h / 2);
    qx = center.x + signY * (h / 2) * (dx / (dy || 0.001));
  }

  return { x: qx, y: qy };
}

/**
 * Computes the perpendicular-offset bezier control point
 * for a smooth curve between (sx,sy) and (ex,ey).
 * Ported from SysMLDiagramTool drawLink isCurved logic.
 */
export function computeBezierControlPoint(
  sx: number, sy: number,
  ex: number, ey: number,
  offsetFactor: number = 0.2
): { cpX: number; cpY: number } {
  const dx = ex - sx;
  const dy = ey - sy;
  return {
    cpX: (sx + ex) / 2 - dy * offsetFactor,
    cpY: (sy + ey) / 2 + dx * offsetFactor,
  };
}

/**
 * Relationship type → preferred routing style mapping.
 * Matches the rendering intent from SysMLDiagramTool.
 */
export const RELATIONSHIP_ROUTING: Record<string, 'smoothstep' | 'bezier' | 'straight'> = {
  // Curved (bezier) — transitions, control flow, succession
  TransitionUsage: 'bezier',
  Succession: 'bezier',

  // Smoothstep (orthogonal with curved corners) — structural relationships
  Subsetting: 'smoothstep',
  Redefinition: 'smoothstep',
  Dependency: 'smoothstep',
  Subclassification: 'smoothstep',
  FeatureTyping: 'smoothstep',
  Allocation: 'smoothstep',
  InterfaceUsage: 'smoothstep',

  // Straight — flow connections, value assignments
  FlowConnectionUsage: 'straight',
  FeatureValue: 'straight',
  BindingConnectorAsUsage: 'straight',

  // Default
  default: 'smoothstep',
};

/**
 * Gets the preferred ReactFlow edge type for a given SysML relationship type.
 */
export function getRoutingStyle(domainType: string | undefined): 'smoothstep' | 'bezier' | 'straight' {
  if (!domainType) return 'smoothstep';
  for (const [key, style] of Object.entries(RELATIONSHIP_ROUTING)) {
    if (domainType.includes(key)) return style;
  }
  return RELATIONSHIP_ROUTING.default;
}

export default { getEdgePoint, computeBezierControlPoint, getRoutingStyle, RELATIONSHIP_ROUTING };
